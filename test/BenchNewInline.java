import one.nalim.Link;
import one.nalim.Linker;

// A/B throughput bench for functions the extended decoder newly accepts.
// Run twice under identical flags:
//   default            → auto-inline path (body copied into the nmethod)
//   -Dnalim.noinline   → stub path (barrier + shuffle + direct jmp rel32)
// No lambdas — each measurement is its own static method (megamorphic-safe).
//
// Requires java.library.path to contain, in order: the v3-built
// libbench_rng_rust.so directory, then test/ (nalim_bench, nalim_fx_v3).
public class BenchNewInline {

    // C loop function (backward branch) — gcc -O2, 28 bytes.
    @Link(name = "raw_sum_to")
    static native long sumTo(int n);

    // gcc -march=x86-64-v3: BMI2/VEX-encoded integer mixer, branches, loop.
    @Link(name = "fx_mix64_v3")
    static native long mix64(long z);

    // vroundsd — floor without libm.
    @Link(name = "fx_floor_v3")
    static native double floorV3(double x);

    // VEX scalar float chain.
    @Link(name = "fx_lerp_v3")
    static native float lerpV3(float a, float b, float t);

    // branches + forward jmp join.
    @Link(name = "fx_select_v3")
    static native int selectV3(int c, int a, int b);

    // Rust v3 build: VEX+BMI2 splitmix (state in register, pure FFI).
    @Link(name = "rust_splitmix_mix")
    static native long rustMix(long z);

    static final long GAMMA = 0x9E3779B97F4A7C15L;

    public static void main(String[] args) {
        System.out.println("java.version = " + System.getProperty("java.version")
                + " noinline=" + Boolean.getBoolean("nalim.noinline"));
        Linker.loadLibrary("nalim_bench");
        Linker.loadLibrary("nalim_fx_v3");
        Linker.loadLibrary("bench_rng_rust");
        Linker.linkClass(BenchNewInline.class);
        System.out.println("inlined = " + Linker.getInlinedMethodCount() + " of 6");

        // sanity
        if (sumTo(10) != 45) throw new AssertionError("sumTo");
        if (selectV3(1, 2, 3) != 13) throw new AssertionError("select");

        long sink = 0;
        for (int i = 0; i < 3_000_000; i++) {
            sink += sumTo(16);
            sink += mix64(i * GAMMA);
            sink += (long) floorV3(i * 0.7);
            sink += (long) lerpV3(i, 100 - i, 0.25f);
            sink += selectV3(i & 3, i, -i);
            sink += rustMix(i * GAMMA);
        }
        System.out.println("warmup sink = " + sink);

        benchSumTo4();
        benchSumTo16();
        benchSumTo64();
        benchMix64();
        benchRustMix();
        benchFloor();
        benchLerp();
        benchSelect();
    }

    static void benchSumTo4() {
        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int run = 0; run < 5; run++) {
            long s = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < 100_000_000; i++) s += sumTo(4);
            long t1 = System.nanoTime();
            sink = s;
            best = Math.min(best, (t1 - t0) / 100_000_000.0);
        }
        report("raw_sum_to(4)   [C loop fn]", best, sink);
    }

    static void benchSumTo16() {
        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int run = 0; run < 5; run++) {
            long s = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < 50_000_000; i++) s += sumTo(16);
            long t1 = System.nanoTime();
            sink = s;
            best = Math.min(best, (t1 - t0) / 50_000_000.0);
        }
        report("raw_sum_to(16)  [C loop fn]", best, sink);
    }

    static void benchSumTo64() {
        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int run = 0; run < 5; run++) {
            long s = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < 20_000_000; i++) s += sumTo(64);
            long t1 = System.nanoTime();
            sink = s;
            best = Math.min(best, (t1 - t0) / 20_000_000.0);
        }
        report("raw_sum_to(64)  [C loop fn]", best, sink);
    }

    static void benchMix64() {
        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int run = 0; run < 5; run++) {
            long s = 0, st = 0x123456789ABCDEF0L;
            long t0 = System.nanoTime();
            for (int i = 0; i < 100_000_000; i++) { st += GAMMA; s += mix64(st); }
            long t1 = System.nanoTime();
            sink = s;
            best = Math.min(best, (t1 - t0) / 100_000_000.0);
        }
        report("fx_mix64_v3     [BMI2 mixer]", best, sink);
    }

    static void benchRustMix() {
        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int run = 0; run < 5; run++) {
            long s = 0, st = 0x123456789ABCDEF0L;
            long t0 = System.nanoTime();
            for (int i = 0; i < 100_000_000; i++) { st += GAMMA; s += rustMix(st); }
            long t1 = System.nanoTime();
            sink = s;
            best = Math.min(best, (t1 - t0) / 100_000_000.0);
        }
        report("rust_splitmix   [Rust v3]  ", best, sink);
    }

    static void benchFloor() {
        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int run = 0; run < 5; run++) {
            long s = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < 100_000_000; i++) s += (long) floorV3(i * 0.7);
            long t1 = System.nanoTime();
            sink = s;
            best = Math.min(best, (t1 - t0) / 100_000_000.0);
        }
        report("fx_floor_v3     [vroundsd] ", best, sink);
    }

    static void benchLerp() {
        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int run = 0; run < 5; run++) {
            long s = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < 100_000_000; i++) s += (long) lerpV3(i & 0xFF, 256 - (i & 0xFF), 0.25f);
            long t1 = System.nanoTime();
            sink = s;
            best = Math.min(best, (t1 - t0) / 100_000_000.0);
        }
        report("fx_lerp_v3      [VEX float]", best, sink);
    }

    static void benchSelect() {
        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int run = 0; run < 5; run++) {
            long s = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < 100_000_000; i++) s += selectV3(i & 3, i, -i);
            long t1 = System.nanoTime();
            sink = s;
            best = Math.min(best, (t1 - t0) / 100_000_000.0);
        }
        report("fx_select_v3    [branch+jmp]", best, sink);
    }

    static void report(String name, double ns, long sink) {
        System.out.printf("  %-30s : %6.3f ns/op (sink=%d)%n", name, ns, sink);
    }
}
