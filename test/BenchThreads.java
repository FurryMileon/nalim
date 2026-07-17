import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

// Measures cost of Java thread management primitives — to answer the
// question "can Nalim/Rust make this faster?". Spoiler: no. These ARE
// pure Java/OS coordination; the language doesn't matter, the OS syscall
// and the JVM bookkeeping do.
//
// Three scales:
//   A) Thread create + join (one-shot cost of new OS thread)
//   B) Submit empty task to ForkJoinPool / Executor + await (hot path of
//      Async15's per-tick fan-out)
//   C) Fan-out N empty tasks + allOf().join() (Async15's entity-tick
//      batch pattern at N=16)

public class BenchThreads {

    static final int FANOUT_N = 16;            // typical Async15 pool size
    static final int ITER_HOT = 200_000;       // hot-path iters
    static final int ITER_FANOUT = 50_000;
    static final int ITER_THREAD = 5_000;      // thread create is heavy

    static final ExecutorService FIXED = Executors.newFixedThreadPool(FANOUT_N, r -> {
        Thread t = new Thread(r, "Fixed-Pool-Worker");
        t.setDaemon(true);
        return t;
    });

    static final ForkJoinPool FJP = new ForkJoinPool(FANOUT_N);

    static final Runnable NOOP = () -> {};

    // shared mutable target for "did the task run" tests
    static final AtomicInteger SINK = new AtomicInteger();
    static final Runnable INCREMENT = () -> SINK.incrementAndGet();

    public static void main(String[] args) throws Exception {
        System.out.println("java.version = " + System.getProperty("java.version"));

        // warmup
        for (int i = 0; i < 5000; i++) {
            CompletableFuture.runAsync(NOOP).join();
            FIXED.submit(NOOP).get();
            FJP.submit(NOOP).get();
        }

        bench_thread_start_join();
        bench_runAsync();
        bench_fixed_submit_get();
        bench_fjp_submit_join();
        bench_countdown_n();
        bench_fanout_runAsync();
        bench_fanout_fjp();

        FIXED.shutdown();
        FJP.shutdown();
    }

    static void bench_thread_start_join() throws InterruptedException {
        double best = Double.MAX_VALUE;
        for (int r = 0; r < 3; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < ITER_THREAD; i++) {
                Thread t = new Thread(NOOP);
                t.start();
                t.join();
            }
            long t1 = System.nanoTime();
            double ns = (t1 - t0) / (double) ITER_THREAD;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-40s : %8.0f ns/op%n",
                "new Thread().start() + join()", best);
    }

    static void bench_runAsync() {
        double best = Double.MAX_VALUE;
        for (int r = 0; r < 3; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < ITER_HOT; i++) {
                CompletableFuture.runAsync(NOOP).join();
            }
            long t1 = System.nanoTime();
            double ns = (t1 - t0) / (double) ITER_HOT;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-40s : %8.0f ns/op%n",
                "CompletableFuture.runAsync().join()", best);
    }

    static void bench_fixed_submit_get() throws Exception {
        double best = Double.MAX_VALUE;
        for (int r = 0; r < 3; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < ITER_HOT; i++) {
                FIXED.submit(NOOP).get();
            }
            long t1 = System.nanoTime();
            double ns = (t1 - t0) / (double) ITER_HOT;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-40s : %8.0f ns/op%n",
                "FixedThreadPool.submit().get()", best);
    }

    static void bench_fjp_submit_join() {
        double best = Double.MAX_VALUE;
        for (int r = 0; r < 3; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < ITER_HOT; i++) {
                FJP.submit(NOOP).join();
            }
            long t1 = System.nanoTime();
            double ns = (t1 - t0) / (double) ITER_HOT;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-40s : %8.0f ns/op%n",
                "ForkJoinPool.submit().join()", best);
    }

    static void bench_countdown_n() throws Exception {
        // fan-out N empty tasks via FJP, await via CountDownLatch
        double best = Double.MAX_VALUE;
        for (int r = 0; r < 3; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < ITER_FANOUT; i++) {
                CountDownLatch latch = new CountDownLatch(FANOUT_N);
                for (int k = 0; k < FANOUT_N; k++) {
                    FJP.execute(latch::countDown);
                }
                latch.await();
            }
            long t1 = System.nanoTime();
            double ns = (t1 - t0) / (double) ITER_FANOUT;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-40s : %8.0f ns/op (per fan-out of %d)%n",
                "FJP execute × N + CountDownLatch", best, FANOUT_N);
    }

    static void bench_fanout_runAsync() {
        double best = Double.MAX_VALUE;
        for (int r = 0; r < 3; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < ITER_FANOUT; i++) {
                CompletableFuture<?>[] futs = new CompletableFuture[FANOUT_N];
                for (int k = 0; k < FANOUT_N; k++) {
                    futs[k] = CompletableFuture.runAsync(NOOP, FJP);
                }
                CompletableFuture.allOf(futs).join();
            }
            long t1 = System.nanoTime();
            double ns = (t1 - t0) / (double) ITER_FANOUT;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-40s : %8.0f ns/op (per fan-out of %d)%n",
                "CF.runAsync × N + allOf().join()", best, FANOUT_N);
    }

    static void bench_fanout_fjp() {
        double best = Double.MAX_VALUE;
        for (int r = 0; r < 3; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < ITER_FANOUT; i++) {
                CompletableFuture<?>[] futs = new CompletableFuture[FANOUT_N];
                for (int k = 0; k < FANOUT_N; k++) {
                    futs[k] = CompletableFuture.runAsync(NOOP, FJP);
                }
                // join individually — closer to Async15's pumpUntilDone
                for (CompletableFuture<?> f : futs) f.join();
            }
            long t1 = System.nanoTime();
            double ns = (t1 - t0) / (double) ITER_FANOUT;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-40s : %8.0f ns/op (per fan-out of %d)%n",
                "CF.runAsync × N + sequential join", best, FANOUT_N);
    }
}
