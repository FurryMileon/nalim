import one.nalim.Library;
import one.nalim.Link;
import one.nalim.Linker;

import java.lang.management.ManagementFactory;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;

// Head-to-head: Java ForkJoinPool (Async15's ParallelProcessor pattern)
// vs Rust rayon thread pool (via Nalim), same workload.
//
// Both pools have 16 workers. Same task counts. Same work-per-task
// (Math.sin / .sin() respectively). We measure:
//   1) empty-task fan-out — pure orchestration overhead
//   2) sin-work fan-out  — end-to-end including the actual work
//
// Per-iteration we record ns/tick; report min and p50 over many runs.

@Library("bench_pool_rust")
public class BenchPool {

    @Link static native int rust_pool_workers();
    @Link static native int rust_pool_warmup();
    @Link static native int rust_empty_tick(int taskCount);
    @Link static native long rust_pool_tick(int taskCount, int workPerTask);
    @Link static native long rust_pool_tick_iter(int taskCount, int workPerTask);

    static final int WORKERS = 16;
    static final ForkJoinPool JFP = new ForkJoinPool(WORKERS);

    public static void main(String[] args) {
        System.out.println("java.version = " + System.getProperty("java.version"));
        System.out.println("availableProcessors = " + Runtime.getRuntime().availableProcessors());
        Linker.linkClass(BenchPool.class);
        System.out.println("rust pool workers = " + rust_pool_workers());

        // pre-warm both pools (start worker threads, take their one-shot cost off the clock)
        rust_pool_warmup();
        rust_pool_warmup();
        for (int i = 0; i < 50; i++) {
            javaEmptyTick(WORKERS);
            javaSinTick(WORKERS, 100);
        }

        System.out.println();
        System.out.println("=== empty-task fan-out (pure orchestration overhead) ===");
        for (int n : new int[]{16, 64, 256, 1024}) {
            benchPair("N=" + n,
                    () -> javaEmptyTick(n),
                    () -> rust_empty_tick(n));
        }

        System.out.println();
        System.out.println("=== sin-work fan-out (each task does W sin ops) ===");
        for (int n : new int[]{16, 64, 256, 1024}) {
            for (int w : new int[]{100, 1000}) {
                final int nn = n, ww = w;
                System.out.printf("--- N=%d tasks, W=%d work/task (total sin ops/tick = %d) ---%n",
                        n, w, (long) n * w);
                benchTriple("N=" + n + " W=" + w,
                        () -> javaSinTick(nn, ww),
                        () -> rust_pool_tick(nn, ww),
                        () -> rust_pool_tick_iter(nn, ww));
            }
        }

        System.out.println();
        System.out.println("=== memory snapshot ===");
        Runtime rt = Runtime.getRuntime();
        long heap = rt.totalMemory() - rt.freeMemory();
        long pid = ProcessHandle.current().pid();
        System.out.printf("Java heap in use        : %.2f MB%n", heap / 1024.0 / 1024.0);
        System.out.printf("Process PID             : %d  (peek RSS: cat /proc/%d/status | grep VmRSS)%n",
                pid, pid);
        long gcCount = 0, gcTime = 0;
        for (var b : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += b.getCollectionCount();
            gcTime  += b.getCollectionTime();
        }
        System.out.printf("GC total collections    : %d (%d ms)%n", gcCount, gcTime);

        JFP.shutdown();
    }

    // ---- Java side ---------------------------------------------------------

    static int javaEmptyTick(int taskCount) {
        CountDownLatch latch = new CountDownLatch(taskCount);
        for (int i = 0; i < taskCount; i++) {
            JFP.execute(latch::countDown);
        }
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return taskCount;
    }

    static long javaSinTick(int taskCount, int workPerTask) {
        AtomicLong sink = new AtomicLong();
        CompletableFuture<?>[] futs = new CompletableFuture[taskCount];
        for (int seed = 0; seed < taskCount; seed++) {
            final int s = seed;
            final int w = workPerTask;
            futs[seed] = CompletableFuture.runAsync(() -> {
                double acc = 0;
                for (int i = 0; i < w; i++) {
                    acc += Math.sin((double) s + (double) i * 0.01);
                }
                sink.getAndAdd(Double.doubleToRawLongBits(acc));
            }, JFP);
        }
        for (CompletableFuture<?> f : futs) f.join();
        return sink.get();
    }

    // ---- timing harness ---------------------------------------------------

    interface Tick { long run(); }

    static long bestNs(Tick t, int iters) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < iters; i++) {
            long a = System.nanoTime();
            long sink = t.run();
            long b = System.nanoTime();
            long d = b - a;
            if (d < best) best = d;
            if (sink == 0xCAFEBABEL) System.out.println();   // optimizer block
        }
        return best;
    }

    static long medianNs(Tick t, int iters) {
        long[] samples = new long[iters];
        for (int i = 0; i < iters; i++) {
            long a = System.nanoTime();
            long sink = t.run();
            long b = System.nanoTime();
            samples[i] = b - a;
            if (sink == 0xCAFEBABEL) System.out.println();
        }
        java.util.Arrays.sort(samples);
        return samples[iters / 2];
    }

    static void benchPair(String label, Tick java, Tick rust) {
        // warmup
        for (int i = 0; i < 200; i++) { java.run(); rust.run(); }
        int iters = 3000;
        long jMin = bestNs(java, iters);
        long jP50 = medianNs(java, iters);
        long rMin = bestNs(rust, iters);
        long rP50 = medianNs(rust, iters);
        System.out.printf("  %-14s Java FJP: min=%7d ns p50=%7d ns   |  Rust pool: min=%7d ns p50=%7d ns   ratio(p50)=%.2fx%n",
                label, jMin, jP50, rMin, rP50, (double) jP50 / (double) rP50);
    }

    static void benchTriple(String label, Tick java, Tick rustSpawn, Tick rustIter) {
        for (int i = 0; i < 100; i++) { java.run(); rustSpawn.run(); rustIter.run(); }
        int iters = 1500;
        long jMin = bestNs(java, iters);
        long jP50 = medianNs(java, iters);
        long rsMin = bestNs(rustSpawn, iters);
        long rsP50 = medianNs(rustSpawn, iters);
        long riMin = bestNs(rustIter, iters);
        long riP50 = medianNs(rustIter, iters);
        System.out.printf("  Java FJP        min=%9d ns  p50=%9d ns%n", jMin, jP50);
        System.out.printf("  Rust scope+spawn min=%9d ns  p50=%9d ns  (vs Java p50 = %.2fx)%n",
                rsMin, rsP50, (double) jP50 / (double) rsP50);
        System.out.printf("  Rust par_iter   min=%9d ns  p50=%9d ns  (vs Java p50 = %.2fx)%n",
                riMin, riP50, (double) jP50 / (double) riP50);
    }
}
