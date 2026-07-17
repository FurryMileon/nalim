import one.nalim.Code;
import one.nalim.Linker;

// Cycle-accurate variant bench using rdtsc directly. Tests whether different
// nmethod body layouts (size, alignment, instruction mix, padding placement)
// change per-call cost. Goal: find any layout that gives a measurable
// improvement, or confirm the 1.0 ns / ~5 cycle floor.
//
// All variants compute the same value (a + b → eax, return) but use different
// byte patterns to test:
//   - body size effects (4 vs 8 vs 16 vs 64 bytes)
//   - padding placement (none, before, after)
//   - instruction mix (lea-only vs add+ret vs movs)
//   - alignment of trailing ret (1B vs 16B from start)
//   - rep ret vs ret (GCC's old alignment workaround)
//
// Java x86-64 calling conv: a in rsi, b in rdx, return in eax.
// Loop pattern is identical across variants (sum += variantN(1, i)) so JIT
// inlining + loop dependency chain is identical — only the nmethod body differs.

public class BenchVariants {

    // V1: minimum body — 4 bytes
    //   8d 04 16     lea eax, [rsi+rdx]
    //   c3           ret
    @Code("8d0416c3")
    public static native int v1_min(int a, int b);

    // V2: 4-byte body + 4 NOP padding AFTER ret (dead bytes)
    //   8d 04 16 c3 90 90 90 90
    @Code("8d0416c3 90909090")
    public static native int v2_pad4_after(int a, int b);

    // V3: 4-byte body + 12 NOPs (total 16B aligned size)
    @Code("8d0416c3 909090909090909090909090")
    public static native int v3_pad12_after(int a, int b);

    // V4: 4-byte body + 60 NOPs (total 64B = L1 cache line)
    @Code("8d0416c3" +
          "90909090909090909090909090909090909090909090909090909090909090" +
          "9090909090909090909090909090909090909090909090909090909090909090")
    public static native int v4_padline_after(int a, int b);

    // V5: alternative encoding — mov eax, esi; add eax, edx; ret (5 bytes)
    //   89 f0        mov eax, esi
    //   01 d0        add eax, edx
    //   c3           ret
    @Code("89f0 01d0 c3")
    public static native int v5_mov_add(int a, int b);

    // V6: rep ret (0xF3 0xC3) instead of plain ret — GCC's old alignment fix.
    //   8d 04 16 f3 c3
    @Code("8d0416 f3c3")
    public static native int v6_rep_ret(int a, int b);

    // V7: lea via different reg pair — same semantics, same byte count
    //   8d 04 32     lea eax, [rdx+rsi]
    //   c3           ret
    @Code("8d0432c3")
    public static native int v7_swapped_lea(int a, int b);

    // V8: explicit xor eax, eax (clear) + lea — slightly larger, tests JIT
    // reordering possibilities
    //   31 c0        xor eax, eax
    //   8d 04 16     lea eax, [rsi+rdx]
    //   c3           ret
    @Code("31c0 8d0416 c3")
    public static native int v8_xor_lea(int a, int b);

    // V9: 2x ret prefix — does the CPU return-stack-buffer get confused?
    //   8d 04 16 c3 c3
    @Code("8d0416 c3c3")
    public static native int v9_double_ret(int a, int b);

    // V10: minimal noop returning 42 — control measurement; no add involved
    //   b8 2a 00 00 00  mov eax, 42
    //   c3              ret
    @Code("b82a000000 c3")
    public static native int v10_const42(int a, int b);

    private static final int N = 200_000_000;
    private static final int WARMUP = 5_000_000;

    public static void main(String[] args) {
        System.out.println("java.version = " + System.getProperty("java.version"));
        Linker.linkClass(BenchVariants.class);

        // sanity
        if (v1_min(3, 4) != 7) throw new AssertionError("v1: " + v1_min(3, 4));
        if (v2_pad4_after(3, 4) != 7) throw new AssertionError("v2");
        if (v3_pad12_after(3, 4) != 7) throw new AssertionError("v3");
        if (v4_padline_after(3, 4) != 7) throw new AssertionError("v4");
        if (v5_mov_add(3, 4) != 7) throw new AssertionError("v5: " + v5_mov_add(3, 4));
        if (v6_rep_ret(3, 4) != 7) throw new AssertionError("v6");
        if (v7_swapped_lea(3, 4) != 7) throw new AssertionError("v7");
        if (v8_xor_lea(3, 4) != 7) throw new AssertionError("v8");
        if (v9_double_ret(3, 4) != 7) throw new AssertionError("v9");
        if (v10_const42(0, 0) != 42) throw new AssertionError("v10: " + v10_const42(0, 0));
        System.out.println("correctness OK");

        long sink = 0;
        for (int i = 0; i < WARMUP; i++) {
            sink += v1_min(1, i) + v2_pad4_after(1, i) + v3_pad12_after(1, i)
                  + v4_padline_after(1, i) + v5_mov_add(1, i) + v6_rep_ret(1, i)
                  + v7_swapped_lea(1, i) + v8_xor_lea(1, i) + v9_double_ret(1, i)
                  + v10_const42(1, i);
        }
        System.out.println("warmup sink = " + sink);

        bench_v1();   bench_v2();   bench_v3();   bench_v4();
        bench_v5();   bench_v6();   bench_v7();   bench_v8();
        bench_v9();   bench_v10();
    }

    static void bench_v1()  { double r = run("v1_min            4B lea+ret",        ()->v1_min(1, 0)); }
    static void bench_v2()  { double r = run("v2_pad4_after     8B lea+ret+4NOP",   ()->v2_pad4_after(1, 0)); }
    static void bench_v3()  { double r = run("v3_pad12_after   16B lea+ret+12NOP",  ()->v3_pad12_after(1, 0)); }
    static void bench_v4()  { double r = run("v4_padline_after 64B lea+ret+60NOP",  ()->v4_padline_after(1, 0)); }
    static void bench_v5()  { double r = run("v5_mov_add        5B mov+add+ret",    ()->v5_mov_add(1, 0)); }
    static void bench_v6()  { double r = run("v6_rep_ret        5B lea+rep_ret",    ()->v6_rep_ret(1, 0)); }
    static void bench_v7()  { double r = run("v7_swapped_lea    4B lea[rdx+rsi]",   ()->v7_swapped_lea(1, 0)); }
    static void bench_v8()  { double r = run("v8_xor_lea        6B xor+lea+ret",    ()->v8_xor_lea(1, 0)); }
    static void bench_v9()  { double r = run("v9_double_ret     5B lea+ret+ret",    ()->v9_double_ret(1, 0)); }
    static void bench_v10() { double r = run("v10_const42       6B mov_imm+ret",    ()->v10_const42(1, 0)); }

    interface IntCall { int call(); }
    static double run(String name, IntCall fn) {
        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int run = 0; run < 5; run++) {
            int s = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) s += fn.call();
            long t1 = System.nanoTime();
            sink = s;
            double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-36s : %6.3f ns/op (sink=%d)%n", name, best, sink);
        return best;
    }
}
