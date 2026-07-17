import one.nalim.Library;
import one.nalim.Link;
import one.nalim.Linker;

import java.util.concurrent.locks.ReentrantLock;

// Compares Nalim's call overhead against a few flavours of Java
// synchronization, on uncontended hot paths. All variants do the same
// work (a + b returning int) — anything extra is pure synchronisation /
// FFI overhead.

@Library("nalim_bench")
public class BenchVsSync {

    @Link
    public static native int raw_add(int a, int b);

    static int plain_add(int a, int b) { return a + b; }

    static synchronized int sync_static_add(int a, int b) { return a + b; }

    private static final Object LOCK = new Object();
    static int sync_block_add(int a, int b) {
        synchronized (LOCK) { return a + b; }
    }

    private static final ReentrantLock RLOCK = new ReentrantLock();
    static int rlock_add(int a, int b) {
        RLOCK.lock();
        try { return a + b; } finally { RLOCK.unlock(); }
    }

    // Instance-method variant — different lock acquire path (vs static).
    private final Object instLock = new Object();
    int sync_inst_add(int a, int b) {
        synchronized (instLock) { return a + b; }
    }

    private static final int N = 200_000_000;
    private static final int WARMUP = 5_000_000;

    public static void main(String[] args) {
        System.out.println("java.version = " + System.getProperty("java.version"));
        Linker.linkClass(BenchVsSync.class);

        BenchVsSync inst = new BenchVsSync();

        long s = 0;
        for (int i = 0; i < WARMUP; i++) {
            s += raw_add(1, i);
            s += plain_add(1, i);
            s += sync_static_add(1, i);
            s += sync_block_add(1, i);
            s += rlock_add(1, i);
            s += inst.sync_inst_add(1, i);
        }
        System.out.println("warmup sink = " + s);

        bench_plain();
        bench_sync_static();
        bench_sync_block();
        bench_sync_inst(inst);
        bench_rlock();
        bench_nalim();
    }

    static void bench_plain() {
        double best = Double.MAX_VALUE;
        int sink = 0;
        for (int r = 0; r < 5; r++) {
            int t = 0; long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) t += plain_add(1, i);
            long t1 = System.nanoTime();
            sink = t;
            double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-32s : %6.3f ns/op (sink=%d)%n",
                "plain Java a+b", best, sink);
    }
    static void bench_sync_static() {
        double best = Double.MAX_VALUE;
        int sink = 0;
        for (int r = 0; r < 5; r++) {
            int t = 0; long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) t += sync_static_add(1, i);
            long t1 = System.nanoTime();
            sink = t;
            double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-32s : %6.3f ns/op (sink=%d)%n",
                "synchronized static method", best, sink);
    }
    static void bench_sync_block() {
        double best = Double.MAX_VALUE;
        int sink = 0;
        for (int r = 0; r < 5; r++) {
            int t = 0; long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) t += sync_block_add(1, i);
            long t1 = System.nanoTime();
            sink = t;
            double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-32s : %6.3f ns/op (sink=%d)%n",
                "synchronized(staticLock) block", best, sink);
    }
    static void bench_sync_inst(BenchVsSync inst) {
        double best = Double.MAX_VALUE;
        int sink = 0;
        for (int r = 0; r < 5; r++) {
            int t = 0; long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) t += inst.sync_inst_add(1, i);
            long t1 = System.nanoTime();
            sink = t;
            double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-32s : %6.3f ns/op (sink=%d)%n",
                "synchronized(instLock) block", best, sink);
    }
    static void bench_rlock() {
        double best = Double.MAX_VALUE;
        int sink = 0;
        for (int r = 0; r < 5; r++) {
            int t = 0; long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) t += rlock_add(1, i);
            long t1 = System.nanoTime();
            sink = t;
            double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-32s : %6.3f ns/op (sink=%d)%n",
                "ReentrantLock lock/add/unlock", best, sink);
    }
    static void bench_nalim() {
        double best = Double.MAX_VALUE;
        int sink = 0;
        for (int r = 0; r < 5; r++) {
            int t = 0; long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) t += raw_add(1, i);
            long t1 = System.nanoTime();
            sink = t;
            double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-32s : %6.3f ns/op (sink=%d)%n",
                "Nalim @Link raw_add", best, sink);
    }
}
