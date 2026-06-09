import one.nalim.Code;
import one.nalim.Link;
import one.nalim.Library;
import one.nalim.Linker;

// Benchmarks Nalim's @Link path (call into a C function in libnalim_bench.so)
// against Nalim's @Code path (machine code inlined directly into the nmethod).
//
// @Link path:
//   nmethod entry:
//       [arg shuffle Java→C calling conv]  ; 2-4 mov
//       mov rax, addr                       ; 10 B
//       jmp rax                             ; 2 B
//   C function:
//       lea eax, [rdi+rsi]; ret             ; the real work
//
// @Code path:
//   nmethod entry:
//       [user-supplied raw bytes, expecting Java calling conv]
//
// On x86-64 Linux Java passes first two int args in rsi/rdx, so the body of
// add(a,b) becomes lea eax, [rsi+rdx]; ret  →  "8d 04 16 c3" (4 bytes).
@Library("nalim_bench")
public class BenchInlineCode {

    // --- @Link path (Nalim's wrapper into libnalim_bench.so functions) ---
    @Link public static native int raw_add(int a, int b);
    @Link public static native int raw_abs(int x);
    @Link public static native int raw_noop();

    // --- @Code path (raw machine bytes inlined into the nmethod) ---
    //   8d 04 16        lea  eax, [rsi+rdx]   ; eax = a + b   (a=rsi, b=rdx)
    //   c3              ret
    @Code("8d0416c3")
    public static native int inline_add(int a, int b);

    //   89 f0           mov  eax, esi         ; eax = x
    //   f7 d8           neg  eax              ; eax = -x
    //   0f 48 c6        cmovs eax, esi        ; if eax < 0 (x was positive) eax = x
    //   c3              ret
    @Code("89f0 f7d8 0f48c6 c3")
    public static native int inline_abs(int x);

    //   b8 2a 00 00 00  mov  eax, 42          ; constant return
    //   c3              ret
    @Code("b82a000000 c3")
    public static native int inline_noop();

    private static final int N = 50_000_000;

    public static void main(String[] args) {
        System.out.println("java.version = " + System.getProperty("java.version"));
        Linker.linkClass(BenchInlineCode.class);

        // sanity check correctness first
        if (raw_add(3, 4) != 7)            throw new AssertionError("raw_add: "    + raw_add(3, 4));
        if (inline_add(3, 4) != 7)         throw new AssertionError("inline_add: " + inline_add(3, 4));
        if (raw_abs(-7) != 7)              throw new AssertionError("raw_abs");
        if (inline_abs(-7) != 7)           throw new AssertionError("inline_abs: " + inline_abs(-7));
        if (inline_abs(5) != 5)            throw new AssertionError("inline_abs+:" + inline_abs(5));
        if (raw_noop() != 42)              throw new AssertionError("raw_noop");
        if (inline_noop() != 42)           throw new AssertionError("inline_noop: " + inline_noop());
        System.out.println("correctness OK");

        // warmup
        long w = 0;
        for (int i = 0; i < 1_000_000; i++) {
            w += raw_add(1, i) + inline_add(1, i);
            w += raw_abs(-i) + inline_abs(-i);
            w += raw_noop() + inline_noop();
        }
        System.out.println("warmup sink = " + w);

        bench("raw_add    @Link",  () -> raw_add(1, 2));
        bench("inline_add @Code",  () -> inline_add(1, 2));
        System.out.println();
        bench("raw_abs    @Link",  () -> raw_abs(-7));
        bench("inline_abs @Code",  () -> inline_abs(-7));
        System.out.println();
        bench("raw_noop   @Link",  () -> raw_noop());
        bench("inline_noop @Code", () -> inline_noop());
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
