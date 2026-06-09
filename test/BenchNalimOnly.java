import one.nalim.Library;
import one.nalim.Link;
import one.nalim.Linker;

@Library("nalim_bench")
public class BenchNalimOnly {

    @Link public static native int raw_add(int a, int b);
    @Link public static native int raw_abs(int x);
    @Link public static native int raw_noop();

    private static final int N = 20_000_000;
    private static final int WARMUP = 500_000;

    public static void main(String[] args) {
        System.out.println("java.version = " + System.getProperty("java.version"));
        Linker.linkClass(BenchNalimOnly.class);

        long s = 0;
        for (int i = 0; i < WARMUP; i++) {
            s += raw_add(1, i);
            s += raw_abs(-i);
            s += raw_noop();
        }
        System.out.println("warmup sink = " + s);

        bench("raw_add  Nalim", () -> raw_add(1, 2));
        bench("raw_abs  Nalim", () -> raw_abs(-7));
        bench("raw_noop Nalim", () -> raw_noop());
    }

    interface IntCall { int call(); }

    static void bench(String name, IntCall fn) {
        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int run = 0; run < 5; run++) {
            long s = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) s += fn.call();
            long t1 = System.nanoTime();
            sink = s;
            double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-22s : %6.3f ns/op   (sink=%d)%n", name, best, sink);
    }
}
