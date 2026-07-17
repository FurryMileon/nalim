import one.nalim.Code;
import one.nalim.Linker;

// Cycle-accurate variant bench WITHOUT lambda harness — JIT couldn't keep up
// when 10+ lambda variants all targeted the same IntCall interface (polymorphic
// inline cache → vtable dispatch → ~3 ns added to every measurement). Now each
// variant has its own direct measurement method, no shared interface.

public class BenchVariantsNoLambda {

    @Code("8d0416c3")
    public static native int v1_min(int a, int b);

    @Code("8d0416c3 90909090")
    public static native int v2_pad4(int a, int b);

    @Code("8d0416c3 909090909090909090909090")
    public static native int v3_pad12(int a, int b);

    @Code("89f0 01d0 c3")
    public static native int v5_mov_add(int a, int b);

    @Code("8d0416 f3c3")
    public static native int v6_rep_ret(int a, int b);

    @Code("8d0432c3")
    public static native int v7_swap(int a, int b);

    @Code("31c0 8d0416 c3")
    public static native int v8_xor_lea(int a, int b);

    @Code("b82a000000 c3")
    public static native int v10_const42(int a, int b);

    private static final int N = 200_000_000;
    private static final int WARMUP = 5_000_000;

    public static void main(String[] args) {
        System.out.println("java.version = " + System.getProperty("java.version"));
        Linker.linkClass(BenchVariantsNoLambda.class);
        // Warmup all variants
        long sink = 0;
        for (int i = 0; i < WARMUP; i++) {
            sink += v1_min(1, i) + v2_pad4(1, i) + v3_pad12(1, i)
                  + v5_mov_add(1, i) + v6_rep_ret(1, i) + v7_swap(1, i)
                  + v8_xor_lea(1, i) + v10_const42(1, i);
        }
        System.out.println("warmup sink = " + sink);

        bench_v1();
        bench_v2();
        bench_v3();
        bench_v5();
        bench_v6();
        bench_v7();
        bench_v8();
        bench_v10();
        // also rerun v1 at end to confirm not "first hot, others cold"
        bench_v1_again();
    }

    static void bench_v1() {
        double best = Double.MAX_VALUE; int s = 0;
        for (int r = 0; r < 5; r++) {
            int t = 0; long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) t += v1_min(1, i);
            long t1 = System.nanoTime();
            s = t; double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-30s : %6.3f ns/op (sink=%d)%n", "v1_min     4B lea+ret",       best, s);
    }
    static void bench_v2() {
        double best = Double.MAX_VALUE; int s = 0;
        for (int r = 0; r < 5; r++) {
            int t = 0; long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) t += v2_pad4(1, i);
            long t1 = System.nanoTime();
            s = t; double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-30s : %6.3f ns/op (sink=%d)%n", "v2_pad4    8B lea+ret+4NOP",  best, s);
    }
    static void bench_v3() {
        double best = Double.MAX_VALUE; int s = 0;
        for (int r = 0; r < 5; r++) {
            int t = 0; long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) t += v3_pad12(1, i);
            long t1 = System.nanoTime();
            s = t; double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-30s : %6.3f ns/op (sink=%d)%n", "v3_pad12  16B lea+ret+12NOP", best, s);
    }
    static void bench_v5() {
        double best = Double.MAX_VALUE; int s = 0;
        for (int r = 0; r < 5; r++) {
            int t = 0; long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) t += v5_mov_add(1, i);
            long t1 = System.nanoTime();
            s = t; double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-30s : %6.3f ns/op (sink=%d)%n", "v5_mov_add 5B mov+add+ret",   best, s);
    }
    static void bench_v6() {
        double best = Double.MAX_VALUE; int s = 0;
        for (int r = 0; r < 5; r++) {
            int t = 0; long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) t += v6_rep_ret(1, i);
            long t1 = System.nanoTime();
            s = t; double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-30s : %6.3f ns/op (sink=%d)%n", "v6_rep_ret 5B lea+rep_ret",   best, s);
    }
    static void bench_v7() {
        double best = Double.MAX_VALUE; int s = 0;
        for (int r = 0; r < 5; r++) {
            int t = 0; long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) t += v7_swap(1, i);
            long t1 = System.nanoTime();
            s = t; double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-30s : %6.3f ns/op (sink=%d)%n", "v7_swap    4B lea[rdx+rsi]",  best, s);
    }
    static void bench_v8() {
        double best = Double.MAX_VALUE; int s = 0;
        for (int r = 0; r < 5; r++) {
            int t = 0; long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) t += v8_xor_lea(1, i);
            long t1 = System.nanoTime();
            s = t; double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-30s : %6.3f ns/op (sink=%d)%n", "v8_xor_lea 6B xor+lea+ret",   best, s);
    }
    static void bench_v10() {
        double best = Double.MAX_VALUE; int s = 0;
        for (int r = 0; r < 5; r++) {
            int t = 0; long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) t += v10_const42(1, i);
            long t1 = System.nanoTime();
            s = t; double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-30s : %6.3f ns/op (sink=%d)%n", "v10_const42 6B mov_imm+ret", best, s);
    }
    static void bench_v1_again() {
        double best = Double.MAX_VALUE; int s = 0;
        for (int r = 0; r < 5; r++) {
            int t = 0; long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) t += v1_min(1, i);
            long t1 = System.nanoTime();
            s = t; double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-30s : %6.3f ns/op (sink=%d)%n", "v1_min_AGAIN (sanity)",       best, s);
    }
}
