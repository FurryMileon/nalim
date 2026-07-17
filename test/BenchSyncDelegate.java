import one.nalim.Library;
import one.nalim.Link;
import one.nalim.Linker;

@Library("nalim_bench_locks")
public class BenchSyncDelegate {

    @Link static native int pmutex_add(int a, int b);
    @Link static native int spinlock_add(int a, int b);
    @Link static native int rmw_add(int a, int b);

    // Java baselines
    static int plain_add(int a, int b) { return a + b; }
    static synchronized int sync_add(int a, int b) { return a + b; }

    private static final Object LOCK = new Object();
    static int sync_block(int a, int b) {
        synchronized (LOCK) { return a + b; }
    }

    private static final int N = 200_000_000;
    private static final int WARMUP = 5_000_000;

    public static void main(String[] args) {
        System.out.println("java.version = " + System.getProperty("java.version"));
        Linker.linkClass(BenchSyncDelegate.class);

        // sanity
        if (pmutex_add(3, 4) != 7) throw new AssertionError("pmutex");
        if (spinlock_add(3, 4) != 7) throw new AssertionError("spin");
        if (rmw_add(3, 4) != 7) throw new AssertionError("rmw");
        System.out.println("correctness OK");

        long s = 0;
        for (int i = 0; i < WARMUP; i++) {
            s += plain_add(1, i);
            s += sync_add(1, i);
            s += sync_block(1, i);
            s += pmutex_add(1, i);
            s += spinlock_add(1, i);
            s += rmw_add(1, i);
        }
        System.out.println("warmup sink = " + s);

        bench("plain Java a+b",                 () -> plain_add(1, 0));
        bench("synchronized static method",     () -> sync_add(1, 0));
        bench("synchronized(staticLock) block", () -> sync_block(1, 0));
        bench("Nalim @Link pmutex_add",         () -> pmutex_add(1, 0));
        bench("Nalim @Link spinlock_add",       () -> spinlock_add(1, 0));
        bench("Nalim @Link atomic rmw_add",     () -> rmw_add(1, 0));
    }

    interface IntCall { int call(); }
    static void bench(String name, IntCall fn) {
        double best = Double.MAX_VALUE;
        int sink = 0;
        for (int r = 0; r < 5; r++) {
            int t = 0; long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) t += fn.call();
            long t1 = System.nanoTime();
            sink = t;
            double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-38s : %6.3f ns/op (sink=%d)%n", name, best, sink);
    }
}
