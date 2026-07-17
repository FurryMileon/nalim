import one.nalim.Code;
import one.nalim.Library;
import one.nalim.Link;
import one.nalim.Linker;

// Pushes Nalim to the absolute floor and measures it against pure Java
// and a known JIT intrinsic. Four variants exercised, each calling a no-op
// "add" that just returns a+b:
//
//   1) java_add        -- pure Java method; JIT inlines; ABSOLUTE FLOOR
//   2) intrinsic_abs   -- Math.abs(int), x86 JIT intrinsic; ~0 overhead reference
//   3) link_shuffle    -- Nalim @Link to a SystemV-ABI C function (current default).
//                          Nalim emits "mov edi, esi; mov esi, edx; mov rax, addr; jmp rax"
//   4) link_naked      -- Nalim @Link(naked=true) to a C function compiled with
//                          HotSpot's Java calling convention. Skips the shuffle.
//                          Nalim emits "mov rax, addr; jmp rax".  -2 instr / -1 cycle.
//   5) inline_code     -- Nalim @Code: machine code is the WHOLE nmethod body.
//                          No mov rax, no jmp. Just "lea eax,[rsi+rdx]; ret".
//
// All measurements use direct loop (no lambda dispatch), large warmup,
// taskset-pinned, perf governor. Per-call cost is bench-loop overhead ~0.2ns.

@Library("nalim_bench")
public class BenchFloor {

    @Link(name = "raw_add")
    public static native int link_shuffle_add(int a, int b);   // <-- raw_add in C (SystemV ABI)

    @Link(name = "java_add", naked = true)
    public static native int link_naked_add(int a, int b);     // <-- java_add in C (Java-ABI), naked

    @Code("8d0416c3")  // lea eax, [rsi+rdx]; ret  (4 bytes total, Java calling conv)
    public static native int inline_code_add(int a, int b);

    static int java_add(int a, int b) { return a + b; }

    private static final int N = 200_000_000;
    private static final int WARMUP = 5_000_000;

    public static void main(String[] args) {
        System.out.println("java.version = " + System.getProperty("java.version"));
        // Load the SystemV-ABI lib + the Java-ABI lib
        Linker.loadLibrary("nalim_bench");        // raw_add (SystemV ABI)
        Linker.loadLibrary("nalim_bench_naked");  // java_add (Java ABI)
        Linker.linkClass(BenchFloor.class);

        if (link_shuffle_add(3, 4) != 7) throw new AssertionError("link_shuffle: " + link_shuffle_add(3, 4));
        if (link_naked_add(3, 4)   != 7) throw new AssertionError("link_naked: "   + link_naked_add(3, 4));
        if (inline_code_add(3, 4)  != 7) throw new AssertionError("inline_code: "  + inline_code_add(3, 4));
        if (java_add(3, 4)         != 7) throw new AssertionError("java_add");
        if (Math.abs(-7)           != 7) throw new AssertionError("abs");
        System.out.println("correctness OK");

        long sink = 0;
        for (int i = 0; i < WARMUP; i++) {
            sink += link_shuffle_add(1, i);
            sink += link_naked_add(1, i);
            sink += inline_code_add(1, i);
            sink += java_add(1, i);
            sink += Math.abs(-i);
        }
        System.out.println("warmup sink = " + sink);

        bench_java_add();
        bench_math_abs();
        bench_link_shuffle();
        bench_link_naked();
        bench_inline_code();
    }

    static void bench_java_add() {
        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int run = 0; run < 5; run++) {
            int s = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) s += java_add(1, i);
            long t1 = System.nanoTime();
            sink = s;
            double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-32s : %6.3f ns/op   (sink=%d)%n", "1) java_add (pure Java)", best, sink);
    }

    static void bench_math_abs() {
        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int run = 0; run < 5; run++) {
            int s = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) s += Math.abs(i - N/2);
            long t1 = System.nanoTime();
            sink = s;
            double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-32s : %6.3f ns/op   (sink=%d)%n", "2) Math.abs (JIT intrinsic)", best, sink);
    }

    static void bench_link_shuffle() {
        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int run = 0; run < 5; run++) {
            int s = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) s += link_shuffle_add(1, i);
            long t1 = System.nanoTime();
            sink = s;
            double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-32s : %6.3f ns/op   (sink=%d)%n", "3) @Link  default (with shuffle)", best, sink);
    }

    static void bench_link_naked() {
        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int run = 0; run < 5; run++) {
            int s = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) s += link_naked_add(1, i);
            long t1 = System.nanoTime();
            sink = s;
            double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-32s : %6.3f ns/op   (sink=%d)%n", "4) @Link(naked=true, Java-ABI)", best, sink);
    }

    static void bench_inline_code() {
        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int run = 0; run < 5; run++) {
            int s = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) s += inline_code_add(1, i);
            long t1 = System.nanoTime();
            sink = s;
            double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-32s : %6.3f ns/op   (sink=%d)%n", "5) @Code  (bytes inlined)", best, sink);
    }
}
