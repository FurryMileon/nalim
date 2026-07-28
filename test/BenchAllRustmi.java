import one.nalim.Link;
import one.nalim.Linker;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Full-surface latency bench: every Rustmi math function linked through
 * nalim, one line of ns/op each. Modeled on BenchRustmiVsPanama.
 *
 *   java -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI \
 *        -cp ../nalim.jar:. -Drustmi.so=<path> [-Diters=N] BenchAllRustmi
 *
 * Functions are linked one at a time; a symbol that fails to link is
 * reported as DROPPED instead of failing the whole harness.
 *
 * NOTE: hash_code in librustmi is (i32,i32,i32)->i64 (BlockPos-style),
 * not (long)->long — declared accordingly.
 */
public class BenchAllRustmi {

    // ---- trig ----
    @Link static native float  sin_float(float x);
    @Link static native float  cos_float(float x);
    @Link static native float  sin_double(double x);
    @Link static native float  cos_double(double x);
    @Link static native float  lithium_sin_float(float x);
    @Link static native float  lithium_cos_float(float x);
    @Link static native float  lithium_sin_double(double x);
    @Link static native float  lithium_cos_double(double x);
    // ---- roots / rounding / abs ----
    @Link static native float  sqrt_float(float x);
    @Link static native int    floor_float(float x);
    @Link static native int    floor_double(double x);
    @Link static native long   floor_long(double x);
    @Link static native int    ceil_float(float x);
    @Link static native int    ceil_double(double x);
    @Link static native float  abs_float(float x);
    @Link static native int    abs_int(int x);
    // ---- clamp ----
    @Link static native float  clamp_float(float x, float min, float max);
    @Link static native double clamp_double(double x, double min, double max);
    @Link static native int    clamp_int(int x, int min, int max);
    // ---- lerp family ----
    @Link static native float  lerp_float(float delta, float start, float end);
    @Link static native double lerp_double(double delta, double start, double end);
    @Link static native int    lerp_int(float delta, int start, int end);
    @Link static native double lerp_2(double dx, double dy,
                                      double x00, double x10, double x01, double x11);
    @Link static native double lerp_3(double dx, double dy, double dz,
                                      double x000, double x100, double x010, double x110,
                                      double x001, double x101, double x011, double x111);
    @Link static native float  catmull_rom(float delta, float p0, float p1, float p2, float p3);
    @Link static native float  map_float(float v, float a, float b, float c, float d);
    @Link static native double map_double(double v, double a, double b, double c, double d);
    @Link static native float  get_lerp_progress_float(float value, float start, float end);
    // ---- misc ----
    @Link static native double atan_2(double y, double x);
    @Link static native double fast_inverse_sqrt(double x);
    @Link static native float  fast_inverse_cbrt(float x);
    @Link static native int    hsv_to_argb(float hue, float saturation, float value, int alpha);
    @Link static native float  wrap_degrees_float(float degrees);
    @Link static native double wrap_degrees_double(double degrees);
    @Link static native long   hash_code(int x, int y, int z);   // librustmi: (i32,i32,i32)->i64
    @Link static native int    ideal_hash(int value);
    // ---- table init (called once, not benched) ----
    @Link static native void   init_math_tables(float[] sine, double[] arcSin, double[] cosOfArcSin);

    static final Set<String> LINKED = new TreeSet<>();
    static final Map<String, String> DROPPED = new TreeMap<>();

    static {
        String so = System.getProperty("rustmi.so", "librustmi.so");
        Linker.loadLibrary(new java.io.File(so).getAbsolutePath());

        Method[] methods = BenchAllRustmi.class.getDeclaredMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName));
        for (Method m : methods) {
            if (m.getAnnotation(Link.class) == null) continue;
            try {
                Linker.linkMethod(m);
                LINKED.add(m.getName());
            } catch (Throwable t) {
                DROPPED.put(m.getName(), t.toString());
            }
        }

        if (LINKED.contains("init_math_tables")) {
            float[] sine = new float[65536];
            double[] asin = new double[257];
            double[] cosAsin = new double[257];
            for (int i = 0; i < 65536; i++) sine[i] = (float) Math.sin(i / 10430.378350470453);
            for (int i = 0; i < 257; i++) {
                asin[i] = Math.asin(i / 256.0);
                cosAsin[i] = Math.cos(asin[i]);
            }
            init_math_tables(sine, asin, cosAsin);
        }
    }

    static final int WARMUP = 2_000_000;
    static final int N = Integer.getInteger("iters", 20_000_000);

    interface Op { double run(int i); }

    static int benchedCount = 0;

    static void bench(String name, Op op) {
        if (!LINKED.contains(name)) {
            System.out.printf("%-24s DROPPED: %s%n", name, DROPPED.getOrDefault(name, "not linked"));
            return;
        }
        double sink = 0;
        for (int i = 0; i < WARMUP; i++) sink += op.run(i);
        long t0 = System.nanoTime();
        for (int i = 0; i < N; i++) sink += op.run(i);
        long dt = System.nanoTime() - t0;
        System.out.printf("%-24s %7.2f ns/op   (sink=%.4g)%n", name, (double) dt / N, sink);
        benchedCount++;
    }

    public static void main(String[] args) {
        System.out.println("java.version = " + System.getProperty("java.version"));
        System.out.println("iters = " + N + " (+ " + WARMUP + " warmup) per function");

        long wall0 = System.nanoTime();

        // Angles: ±4.1 rad; degrees: ±3276.8; deltas: [0,1); all inputs finite, vanilla-sane.
        bench("sin_float",           i -> sin_float(((i & 8191) - 4096) * 0.001f));
        bench("cos_float",           i -> cos_float(((i & 8191) - 4096) * 0.001f));
        bench("sin_double",          i -> sin_double(((i & 8191) - 4096) * 0.001));
        bench("cos_double",          i -> cos_double(((i & 8191) - 4096) * 0.001));
        bench("lithium_sin_float",   i -> lithium_sin_float(((i & 8191) - 4096) * 0.001f));
        bench("lithium_cos_float",   i -> lithium_cos_float(((i & 8191) - 4096) * 0.001f));
        bench("lithium_sin_double",  i -> lithium_sin_double(((i & 8191) - 4096) * 0.001));
        bench("lithium_cos_double",  i -> lithium_cos_double(((i & 8191) - 4096) * 0.001));

        bench("sqrt_float",          i -> sqrt_float((i & 0xFFFFF) * 0.25f));
        bench("floor_float",         i -> floor_float(((i & 0xFFFFF) - 524288) * 0.03125f));
        bench("floor_double",        i -> floor_double(((i & 0xFFFFF) - 524288) * 0.03125));
        bench("floor_long",          i -> (double) floor_long(((i & 0xFFFFF) - 524288) * 0.03125));
        bench("ceil_float",          i -> ceil_float(((i & 0xFFFFF) - 524288) * 0.03125f));
        bench("ceil_double",         i -> ceil_double(((i & 0xFFFFF) - 524288) * 0.03125));
        bench("abs_float",           i -> abs_float(((i & 1023) - 512) * 1.5f));
        bench("abs_int",             i -> abs_int((i & 0xFFFF) - 32768));

        bench("clamp_float",         i -> clamp_float(((i & 511) - 256) * 1.0f, -100.0f, 100.0f));
        bench("clamp_double",        i -> clamp_double(((i & 511) - 256) * 1.0, -100.0, 100.0));
        bench("clamp_int",           i -> clamp_int((i & 1023) - 512, -100, 100));

        bench("lerp_float",          i -> lerp_float((i & 1023) * 0.0009765625f, 1.0f, 100.0f));
        bench("lerp_double",         i -> lerp_double((i & 1023) * 0.0009765625, 1.0, 100.0));
        bench("lerp_int",            i -> lerp_int((i & 1023) * 0.0009765625f, 0, 1000));
        bench("lerp_2",              i -> { double d = (i & 1023) * 0.0009765625;
                                            return lerp_2(d, 1.0 - d, 0.0, 1.0, 2.0, 3.0); });
        bench("lerp_3",              i -> { double d = (i & 1023) * 0.0009765625;
                                            return lerp_3(d, 1.0 - d, d * 0.5,
                                                          0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0); });
        bench("catmull_rom",         i -> catmull_rom((i & 1023) * 0.0009765625f, 0.0f, 1.0f, 2.0f, 3.0f));
        bench("map_float",           i -> map_float((i & 1023) * 1.0f, 0.0f, 1024.0f, -1.0f, 1.0f));
        bench("map_double",          i -> map_double((i & 1023) * 1.0, 0.0, 1024.0, -1.0, 1.0));
        bench("get_lerp_progress_float",
                                     i -> get_lerp_progress_float((i & 1023) * 1.0f, 0.0f, 1024.0f));

        bench("atan_2",              i -> atan_2(((i & 1023) - 512) * 0.01, 42.0));
        bench("fast_inverse_sqrt",   i -> fast_inverse_sqrt((i & 0xFFFF) * 0.5 + 1.0));
        bench("fast_inverse_cbrt",   i -> fast_inverse_cbrt((i & 0xFFFF) * 0.5f + 1.0f));
        bench("hsv_to_argb",         i -> hsv_to_argb((i & 1023) * 0.0009765625f, 0.8f, 0.9f, 255));
        bench("wrap_degrees_float",  i -> wrap_degrees_float(((i & 0xFFFF) - 32768) * 0.1f));
        bench("wrap_degrees_double", i -> wrap_degrees_double(((i & 0xFFFF) - 32768) * 0.1));
        bench("hash_code",           i -> (double) hash_code(i & 0xFFFFF, i & 255, (i * 31) & 0xFFFFF));
        bench("ideal_hash",          i -> ideal_hash(i));

        long wallDt = System.nanoTime() - wall0;
        System.out.printf("TOTAL: %d functions benched, %d dropped, %.1f s wall%n",
                benchedCount, DROPPED.size(), wallDt / 1e9);
        if (!DROPPED.isEmpty()) {
            DROPPED.forEach((k, v) -> System.out.println("  dropped " + k + ": " + v));
        }
    }
}
