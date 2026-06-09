import one.nalim.Library;
import one.nalim.Link;
import one.nalim.Linker;

// Benchmarks Oxidizium's Rust math (via patched Nalim) against:
//   - JDK Math.sin / Math.cos
//   - JDK StrictMath.sin / StrictMath.cos
//   - Vanilla-MC-style sine LUT (the very same trick MathHelper.sin uses on the JVM side)
//
// Same binary as the one shipped in oxidizium-nalim-1.2.0.jar — liboxidizium.so —
// loaded directly so we measure the Nalim wrapper + Rust libm path exactly as it runs
// inside the modded MC server.

@Library("oxidizium")
public class BenchOxidizium {

    @Link public static native float  sin_float (float x);
    @Link public static native float  cos_float (float x);
    @Link public static native float  sin_double(double x);
    @Link public static native float  cos_double(double x);
    @Link public static native float  lithium_sin_float (float x);
    @Link public static native float  lithium_cos_float (float x);
    @Link public static native float  lithium_sin_double(double x);
    @Link public static native float  lithium_cos_double(double x);
    @Link public static native float  sqrt_float (float x);
    @Link public static native int    floor_float(float x);
    @Link public static native int    abs_int(int x);
    @Link public static native int    clamp_int(int v, int lo, int hi);
    @Link public static native double atan_2(double y, double x);

    // Vanilla MC-style 16-bit sine LUT (this is literally what MathHelper.sin does in
    // 1.20+ before any optimization mod kicks in — a 64K-entry float table indexed by
    // (int)(angle * 10430.378f) & 0xFFFF).
    private static final float[] LUT = new float[65536];
    static {
        for (int i = 0; i < 65536; i++) {
            LUT[i] = (float) Math.sin(i * Math.PI * 2.0 / 65536.0);
        }
    }
    static float mc_lut_sin(float angle) {
        return LUT[(int)(angle * 10430.378f) & 0xFFFF];
    }
    static float mc_lut_cos(float angle) {
        return LUT[(int)(angle * 10430.378f + 16384.0f) & 0xFFFF];
    }

    private static final int N = 50_000_000;
    private static final int WARMUP = 1_000_000;

    public static void main(String[] args) {
        System.out.println("java.version = " + System.getProperty("java.version"));
        System.out.println("os.arch      = " + System.getProperty("os.arch"));
        Linker.linkClass(BenchOxidizium.class);

        // Warmup all paths so JIT settles on stable code
        double w = 0;
        for (int i = 0; i < WARMUP; i++) {
            float f = (i & 0x3FF) * 0.01f;
            double d = f;
            w += Math.sin(d) + Math.cos(d);
            w += StrictMath.sin(d) + StrictMath.cos(d);
            w += sin_float(f) + cos_float(f);
            w += sin_double(d) + cos_double(d);
            w += lithium_sin_float(f) + lithium_cos_float(f);
            w += lithium_sin_double(d) + lithium_cos_double(d);
            w += mc_lut_sin(f) + mc_lut_cos(f);
            w += sqrt_float(f * 1000f);
        }
        System.out.printf("warmup sink = %.3e%n%n", w);

        System.out.println("--- SIN (float) ---");
        benchF("Math.sin(float→double)",  i -> (float) Math.sin(i * 0.001f));
        benchF("StrictMath.sin(f→d)",     i -> (float) StrictMath.sin(i * 0.001f));
        benchF("mc_lut_sin (LUT)",        i -> mc_lut_sin(i * 0.001f));
        benchF("Oxidizium sin_float",     i -> sin_float(i * 0.001f));
        benchF("Oxidizium lithium_sin_f", i -> lithium_sin_float(i * 0.001f));

        System.out.println("\n--- COS (float) ---");
        benchF("Math.cos(float→double)",  i -> (float) Math.cos(i * 0.001f));
        benchF("StrictMath.cos(f→d)",     i -> (float) StrictMath.cos(i * 0.001f));
        benchF("mc_lut_cos (LUT)",        i -> mc_lut_cos(i * 0.001f));
        benchF("Oxidizium cos_float",     i -> cos_float(i * 0.001f));
        benchF("Oxidizium lithium_cos_f", i -> lithium_cos_float(i * 0.001f));

        System.out.println("\n--- SIN (double) ---");
        benchF("Math.sin(double)",           i -> (float) Math.sin(i * 0.001));
        benchF("Oxidizium sin_double",       i -> sin_double(i * 0.001));
        benchF("Oxidizium lithium_sin_d",    i -> lithium_sin_double(i * 0.001));

        System.out.println("\n--- LEAF int ops ---");
        benchF("Math.abs(int)",       i -> Math.abs(i - 25_000_000));
        benchF("Oxidizium abs_int",   i -> abs_int(i - 25_000_000));
        benchF("Math.max(min(...))",  i -> Math.max(0, Math.min(10000, i)));
        benchF("Oxidizium clamp_int", i -> clamp_int(i, 0, 10000));

        System.out.println("\n--- sqrt / atan2 ---");
        benchF("Math.sqrt(float→d)",        i -> (float) Math.sqrt(i * 1000.0));
        benchF("Oxidizium sqrt_float",      i -> sqrt_float(i * 1000.0f));
        benchF("Math.atan2",                i -> (float) Math.atan2(i * 0.001, (i+1) * 0.001));
        benchF("Oxidizium atan_2",          i -> (float) atan_2(i * 0.001, (i+1) * 0.001));

        System.out.println("\n--- ACCURACY CHECK (samples) ---");
        for (float a : new float[] { 0f, 0.5f, 1.0f, (float)Math.PI/4, (float)Math.PI/2, (float)Math.PI, 5.0f }) {
            float jSin   = (float) Math.sin(a);
            float lutSin = mc_lut_sin(a);
            float rSin   = sin_float(a);
            float lrSin  = lithium_sin_float(a);
            System.out.printf("  a=%.5f  Math.sin=%+.7f  mc_lut=%+.7f  Rust=%+.7f  RustLut=%+.7f%n",
                    a, jSin, lutSin, rSin, lrSin);
        }
    }

    interface FloatCall { float call(int i); }

    static void benchF(String name, FloatCall fn) {
        double best = Double.MAX_VALUE;
        float lastSink = 0f;
        for (int run = 0; run < 5; run++) {
            float sink = 0f;
            long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) sink += fn.call(i);
            long t1 = System.nanoTime();
            lastSink = sink;
            double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-26s : %7.3f ns/op   (sink=%g)%n", name, best, (double)lastSink);
    }
}
