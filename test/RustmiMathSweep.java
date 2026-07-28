import one.nalim.Link;
import one.nalim.Linker;

import java.util.SplittableRandom;

/**
 * Cross-checks the Rustmi native math library (linked through nalim) against
 * bit-exact Java ports of vanilla MC 26.2 Mth. Also exercises the Java-side
 * table initialization (init_math_tables) added to fix libm-dependent
 * sin/cos/atan2 results.
 *
 * Run from the directory containing librustmi.so (or pass -Drustmi.so=path):
 *   java -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI \
 *        -cp ../nalim.jar:. RustmiMathSweep
 */
public class RustmiMathSweep {

    // ── native links ────────────────────────────────────────────────────
    @Link static native void  init_math_tables(float[] sine, double[] arcSin, double[] cosOfArcSin);
    @Link static native float sin_double(double v);
    @Link static native float cos_double(double v);
    @Link static native float sin_float(float v);
    @Link static native float cos_float(float v);
    @Link static native float lithium_sin_float(float v);
    @Link static native float lithium_cos_float(float v);
    @Link static native float lithium_sin_double(double v);
    @Link static native float lithium_cos_double(double v);
    @Link static native double atan_2(double y, double x);
    @Link static native double fast_inverse_sqrt(double v);
    @Link static native float lerp_float(float d, float s, float e);
    @Link static native double lerp_double(double d, double s, double e);
    @Link static native int    lerp_int(float d, int s, int e);
    @Link static native int    lerp_positive(float d, int s, int e);
    @Link static native double lerp_2(double dx, double dy, double a, double b, double c, double d);
    @Link static native double lerp_3(double dx, double dy, double dz,
                                      double a, double b, double c, double d,
                                      double e, double f, double g, double h);
    @Link static native float  catmull_rom(float delta, float p0, float p1, float p2, float p3);
    @Link static native float  map_float(float v, float os, float oe, float ns, float ne);
    @Link static native double map_double(double v, double os, double oe, double ns, double ne);
    @Link static native float  clamped_map_float(float v, float os, float oe, float ns, float ne);
    @Link static native double clamped_map_double(double v, double os, double oe, double ns, double ne);
    @Link static native float  clamp_float(float v, float lo, float hi);
    @Link static native float  wrap(float value, float maxDeviation);
    @Link static native int    wrap_degrees_int(int d);
    @Link static native float  wrap_degrees_long(long d);
    @Link static native float  wrap_degrees_float(float d);
    @Link static native double wrap_degrees_double(double d);
    @Link static native float  get_lerp_progress_float(float v, float s, float e);
    @Link static native double get_lerp_progress_double(double v, double s, double e);
    @Link static native int    floor_float(float v);
    @Link static native int    floor_double(double v);
    @Link static native long   floor_long(double v);
    @Link static native int    ceil_float(float v);
    @Link static native int    ceil_double(double v);
    @Link static native double clamp_double(double v, double lo, double hi);
    @Link static native float  abs_max_float(float a, float b);
    @Link static native double abs_max(double a, double b);
    @Link static native float  fast_inverse_cbrt(float v);
    @Link static native int    hsv_to_rgb(float h, float s, float v);
    @Link static native int    hsv_to_argb(float h, float s, float v, int alpha);

    // ── vanilla MC 26.2 Mth reference (ported from mapped bytecode) ─────
    static final float[] SIN = new float[65536];
    static final double[] ASIN_TAB = new double[257];
    static final double[] COS_TAB = new double[257];
    static final double FRAC_BIAS = Double.longBitsToDouble(4805340802404319232L);

    static {
        for (int i = 0; i < 65536; i++) SIN[i] = (float) Math.sin(i / 10430.378350470453);
        for (int i = 0; i < 257; i++) {
            double d = Math.asin(i / 256.0);
            ASIN_TAB[i] = d;
            COS_TAB[i] = Math.cos(d);
        }
    }

    static float vSin(double x)  { return SIN[(int) ((long) (x * 10430.378350470453) & 65535L)]; }
    static float vCos(double x)  { return SIN[(int) ((long) (x * 10430.378350470453 + 16384.0) & 65535L)]; }
    static float vSinF(float x)  { return SIN[(int) (x * 10430.378F) & 65535]; }
    static float vCosF(float x)  { return SIN[(int) (x * 10430.378F + 16384.0F) & 65535]; }

    static int vHsvToArgb(float hue, float saturation, float value, int alpha) {
        int i = (int) (hue * 6.0F) % 6;
        float f = hue * 6.0F - (float) i;
        float g = value * (1.0F - saturation);
        float h = value * (1.0F - f * saturation);
        float j = value * (1.0F - (1.0F - f) * saturation);
        float k, l, m;
        switch (i) {
            case 0: k = value; l = j; m = g; break;
            case 1: k = h; l = value; m = g; break;
            case 2: k = g; l = value; m = j; break;
            case 3: k = g; l = h; m = value; break;
            case 4: k = j; l = g; m = value; break;
            case 5: k = value; l = g; m = h; break;
            default: throw new RuntimeException("bad hue");
        }
        int n = clampI((int) (k * 255.0F), 0, 255);
        int o = clampI((int) (l * 255.0F), 0, 255);
        int p = clampI((int) (m * 255.0F), 0, 255);
        return alpha << 24 | n << 16 | o << 8 | p;
    }
    static int clampI(int v, int lo, int hi) { return Math.min(Math.max(v, lo), hi); }

    static float vFastInvCbrt(float x) {
        int i = Float.floatToIntBits(x);
        i = 1419967116 - i / 3;
        float f = Float.intBitsToFloat(i);
        f = 0.6666667F * f + 1.0F / (3.0F * f * f * x);
        return 0.6666667F * f + 1.0F / (3.0F * f * f * x);
    }

    static double vFastInvSqrt(double x) {
        double d = 0.5 * x;
        long l = Double.doubleToRawLongBits(x);
        l = 6910469410427058090L - (l >> 1);
        x = Double.longBitsToDouble(l);
        return x * (1.5 - d * x * x);
    }

    static double vAtan2(double y, double x) {
        double d = x * x + y * y;
        if (Double.isNaN(d)) return Double.NaN;
        boolean bl = y < 0.0; if (bl) y = -y;
        boolean bl2 = x < 0.0; if (bl2) x = -x;
        boolean bl3 = y > x;
        if (bl3) { double t = x; x = y; y = t; }
        double e = vFastInvSqrt(d);
        x *= e; y *= e;
        double f = FRAC_BIAS + y;
        int i = (int) Double.doubleToRawLongBits(f);
        double g = ASIN_TAB[i];
        double h = COS_TAB[i];
        double j = f - FRAC_BIAS;
        double k = y * h - x * j;
        double l = (6.0 + k * k) * k * 0.16666666666666666;
        double m = g + l;
        if (bl3) m = 1.5707963267948966 - m;
        if (bl2) m = Math.PI - m;
        if (bl) m = -m;
        return m;
    }

    static float  vLerp(float d, float s, float e)   { return s + d * (e - s); }
    static double vLerp(double d, double s, double e){ return s + d * (e - s); }
    static int    vLerpInt(float d, int s, int e)    { return s + floor(d * (float) (e - s)); }
    static int    vLerpDiscrete(float d, int s, int e) {
        int i = e - s;
        return s + floor(d * (float) (i - 1)) + (d > 0.0F ? 1 : 0);
    }
    // MC 26.2 Mth.floor(float) = (int) Math.floor((double) f)
    static int floor(float f) { return (int) Math.floor(f); }

    static double vLerp2(double dx, double dy, double a, double b, double c, double d) {
        return vLerp(dy, vLerp(dx, a, b), vLerp(dx, c, d));
    }
    static double vLerp3(double dx, double dy, double dz,
                         double a, double b, double c, double d,
                         double e, double f, double g, double h) {
        return vLerp(dz, vLerp2(dx, dy, a, b, c, d), vLerp2(dx, dy, e, f, g, h));
    }
    static float vCatmullRom(float f, float p0, float p1, float p2, float p3) {
        return 0.5F * (2.0F * p1 + (p2 - p0) * f
                + (2.0F * p0 - 5.0F * p1 + 4.0F * p2 - p3) * f * f
                + (3.0F * p1 - p0 - 3.0F * p2 + p3) * f * f * f);
    }
    static float  vInvLerp(float v, float s, float e)   { return (v - s) / (e - s); }
    static double vInvLerp(double v, double s, double e){ return (v - s) / (e - s); }
    static float  vMap(float v, float os, float oe, float ns, float ne)      { return vLerp(vInvLerp(v, os, oe), ns, ne); }
    static double vMap(double v, double os, double oe, double ns, double ne) { return vLerp(vInvLerp(v, os, oe), ns, ne); }
    static float  vClampedLerp(float d, float s, float e) {
        if (d < 0.0F) return s;
        return d > 1.0F ? e : vLerp(d, s, e);
    }
    static double vClampedLerp(double d, double s, double e) {
        if (d < 0.0) return s;
        return d > 1.0 ? e : vLerp(d, s, e);
    }
    static float  vClampedMap(float v, float os, float oe, float ns, float ne)      { return vClampedLerp(vInvLerp(v, os, oe), ns, ne); }
    static double vClampedMap(double v, double os, double oe, double ns, double ne) { return vClampedLerp(vInvLerp(v, os, oe), ns, ne); }
    static float vClamp(float v, float lo, float hi) { return v < lo ? lo : Math.min(v, hi); }
    static float vWrap(float value, float maxDeviation) {
        return (Math.abs(value % maxDeviation - maxDeviation * 0.5F) - maxDeviation * 0.25F)
                / (maxDeviation * 0.25F);
    }
    static int vWrapDegrees(int d) {
        int i = d % 360;
        if (i >= 180) i -= 360;
        if (i < -180) i += 360;
        return i;
    }
    static float vWrapDegrees(long d) {
        float f = (float) (d % 360L);
        if (f >= 180.0F) f -= 360.0F;
        if (f < -180.0F) f += 360.0F;
        return f;
    }
    static float vWrapDegrees(float f) {
        float g = f % 360.0F;
        if (g >= 180.0F) g -= 360.0F;
        if (g < -180.0F) g += 360.0F;
        return g;
    }
    static double vWrapDegrees(double d) {
        double e = d % 360.0;
        if (e >= 180.0) e -= 360.0;
        if (e < -180.0) e += 360.0;
        return e;
    }

    // ── comparison helpers ──────────────────────────────────────────────
    static int failures = 0;
    static String current = "";
    static long samples = 0;

    static void eq(float got, float want, Object... args) {
        samples++;
        if (Float.floatToRawIntBits(got) != Float.floatToRawIntBits(want)
                && !(Float.isNaN(got) && Float.isNaN(want))) {
            report(got + " != " + want, args);
        }
    }
    static void eq(double got, double want, Object... args) {
        samples++;
        if (Double.doubleToRawLongBits(got) != Double.doubleToRawLongBits(want)
                && !(Double.isNaN(got) && Double.isNaN(want))) {
            report(got + " != " + want, args);
        }
    }
    static void eq(long got, long want, Object... args) {
        samples++;
        if (got != want) report(got + " != " + want, args);
    }
    static final java.util.Map<String, Integer> perTest = new java.util.LinkedHashMap<>();

    static void report(String diff, Object... args) {
        perTest.merge(current, 1, Integer::sum);
        if (failures++ < 12) {
            StringBuilder sb = new StringBuilder("FAIL " + current + ": " + diff + "  args=");
            for (Object a : args) sb.append(a).append(' ');
            System.out.println(sb);
        }
    }

    static double rndD(SplittableRandom r) {
        switch (r.nextInt(8)) {
            case 0:  return r.nextDouble(-1.0, 1.0);
            case 1:  return r.nextDouble(-1e3, 1e3);
            case 2:  return r.nextDouble(-1e9, 1e9);
            case 3:  return r.nextDouble(-1e18, 1e18);
            case 4:  return Double.longBitsToDouble(r.nextLong());  // random bits (NaN/inf/denormal)
            case 5:  return r.nextInt(100) - 50;
            case 6:  return 0.0 * (r.nextBoolean() ? 1 : -1);
            default: return Math.PI * r.nextInt(-8, 8) + r.nextDouble(-1e-15, 1e-15);
        }
    }
    static float rndF(SplittableRandom r) {
        switch (r.nextInt(7)) {
            case 0:  return (float) r.nextDouble(-1.0, 1.0);
            case 1:  return (float) r.nextDouble(-1e3, 1e3);
            case 2:  return (float) r.nextDouble(-1e9, 1e9);
            case 3:  return Float.intBitsToFloat(r.nextInt());      // random bits
            case 4:  return r.nextInt(720) - 360;
            case 5:  return 0.0f * (r.nextBoolean() ? 1 : -1);
            default: return (float) (r.nextDouble(-4.0, 4.0));
        }
    }

    public static void main(String[] args) {
        String so = System.getProperty("rustmi.so", "librustmi.so");
        Linker.loadLibrary(new java.io.File(so).getAbsolutePath());
        Linker.linkClass(RustmiMathSweep.class);

        init_math_tables(SIN.clone(), ASIN_TAB.clone(), COS_TAB.clone());

        int iters = Integer.getInteger("iters", 2_000_000);
        SplittableRandom r = new SplittableRandom(42);

        current = "sin_double";
        for (int i = 0; i < iters; i++) { double x = rndD(r); eq(sin_double(x), vSin(x), x); }
        current = "cos_double";
        for (int i = 0; i < iters; i++) { double x = rndD(r); eq(cos_double(x), vCos(x), x); }
        current = "sin_float";
        for (int i = 0; i < iters; i++) { float x = rndF(r); eq(sin_float(x), vSinF(x), x); }
        current = "cos_float";
        for (int i = 0; i < iters; i++) { float x = rndF(r); eq(cos_float(x), vCosF(x), x); }
        current = "lithium_sin_float";
        for (int i = 0; i < iters; i++) { float x = rndF(r); eq(lithium_sin_float(x), vSinF(x), x); }
        current = "lithium_cos_float";
        for (int i = 0; i < iters; i++) { float x = rndF(r); eq(lithium_cos_float(x), vCosF(x), x); }
        current = "atan_2";
        for (int i = 0; i < iters; i++) {
            double y = rndD(r), x = rndD(r);
            eq(atan_2(y, x), vAtan2(y, x), y, x);
        }
        current = "fast_inverse_sqrt";
        for (int i = 0; i < iters; i++) { double x = rndD(r); eq(fast_inverse_sqrt(x), vFastInvSqrt(x), x); }
        current = "lerp_float";
        for (int i = 0; i < iters; i++) {
            float d = rndF(r), s = rndF(r), e = rndF(r);
            eq(lerp_float(d, s, e), vLerp(d, s, e), d, s, e);
        }
        current = "lerp_double";
        for (int i = 0; i < iters; i++) {
            double d = rndD(r), s = rndD(r), e = rndD(r);
            eq(lerp_double(d, s, e), vLerp(d, s, e), d, s, e);
        }
        current = "lerp_int";
        for (int i = 0; i < iters; i++) {
            float d = rndF(r); int s = r.nextInt(), e = r.nextInt();
            eq(lerp_int(d, s, e), vLerpInt(d, s, e), d, s, e);
        }
        current = "lerp_positive";
        for (int i = 0; i < iters; i++) {
            float d = rndF(r); int s = r.nextInt(), e = r.nextInt();
            eq(lerp_positive(d, s, e), vLerpDiscrete(d, s, e), d, s, e);
        }
        current = "lerp_2";
        for (int i = 0; i < iters; i++) {
            double dx = rndD(r), dy = rndD(r);
            double a = rndD(r), b = rndD(r), c = rndD(r), d = rndD(r);
            eq(lerp_2(dx, dy, a, b, c, d), vLerp2(dx, dy, a, b, c, d), dx, dy);
        }
        current = "lerp_3";
        for (int i = 0; i < iters; i++) {
            double dx = rndD(r), dy = rndD(r), dz = rndD(r);
            double a = rndD(r), b = rndD(r), c = rndD(r), d = rndD(r);
            double e = rndD(r), f = rndD(r), g = rndD(r), h = rndD(r);
            eq(lerp_3(dx, dy, dz, a, b, c, d, e, f, g, h),
               vLerp3(dx, dy, dz, a, b, c, d, e, f, g, h), dx, dy, dz);
        }
        current = "catmull_rom";
        for (int i = 0; i < iters; i++) {
            float d = rndF(r), a = rndF(r), b = rndF(r), c = rndF(r), e = rndF(r);
            eq(catmull_rom(d, a, b, c, e), vCatmullRom(d, a, b, c, e), d, a, b, c, e);
        }
        current = "map_float";
        for (int i = 0; i < iters; i++) {
            float v = rndF(r), a = rndF(r), b = rndF(r), c = rndF(r), e = rndF(r);
            eq(map_float(v, a, b, c, e), vMap(v, a, b, c, e), v, a, b, c, e);
        }
        current = "map_double";
        for (int i = 0; i < iters; i++) {
            double v = rndD(r), a = rndD(r), b = rndD(r), c = rndD(r), e = rndD(r);
            eq(map_double(v, a, b, c, e), vMap(v, a, b, c, e), v, a, b, c, e);
        }
        current = "clamped_map_float";
        for (int i = 0; i < iters; i++) {
            float v = rndF(r), a = rndF(r), b = rndF(r), c = rndF(r), e = rndF(r);
            eq(clamped_map_float(v, a, b, c, e), vClampedMap(v, a, b, c, e), v, a, b, c, e);
        }
        current = "clamped_map_double";
        for (int i = 0; i < iters; i++) {
            double v = rndD(r), a = rndD(r), b = rndD(r), c = rndD(r), e = rndD(r);
            eq(clamped_map_double(v, a, b, c, e), vClampedMap(v, a, b, c, e), v, a, b, c, e);
        }
        current = "clamp_float";
        for (int i = 0; i < iters; i++) {
            float v = rndF(r), lo = rndF(r), hi = rndF(r);
            eq(clamp_float(v, lo, hi), vClamp(v, lo, hi), v, lo, hi);
        }
        current = "wrap";
        for (int i = 0; i < iters; i++) {
            float v = rndF(r), m = rndF(r);
            eq(wrap(v, m), vWrap(v, m), v, m);
        }
        current = "wrap_degrees_int";
        for (int i = 0; i < iters; i++) {
            int v = r.nextInt();
            eq(wrap_degrees_int(v), vWrapDegrees(v), v);
        }
        current = "wrap_degrees_long";
        for (int i = 0; i < iters; i++) {
            long v = r.nextLong();
            eq(wrap_degrees_long(v), vWrapDegrees(v), v);
        }
        current = "wrap_degrees_float";
        for (int i = 0; i < iters; i++) {
            float v = rndF(r);
            eq(wrap_degrees_float(v), vWrapDegrees(v), v);
        }
        current = "wrap_degrees_double";
        for (int i = 0; i < iters; i++) {
            double v = rndD(r);
            eq(wrap_degrees_double(v), vWrapDegrees(v), v);
        }
        current = "get_lerp_progress_float";
        for (int i = 0; i < iters; i++) {
            float v = rndF(r), s = rndF(r), e = rndF(r);
            eq(get_lerp_progress_float(v, s, e), vInvLerp(v, s, e), v, s, e);
        }
        current = "get_lerp_progress_double";
        for (int i = 0; i < iters; i++) {
            double v = rndD(r), s = rndD(r), e = rndD(r);
            eq(get_lerp_progress_double(v, s, e), vInvLerp(v, s, e), v, s, e);
        }
        current = "floor_float";
        for (int i = 0; i < iters; i++) { float v = rndF(r); eq(floor_float(v), (int) Math.floor(v), v); }
        current = "floor_double";
        for (int i = 0; i < iters; i++) { double v = rndD(r); eq(floor_double(v), (int) Math.floor(v), v); }
        current = "floor_long";
        for (int i = 0; i < iters; i++) { double v = rndD(r); eq(floor_long(v), (long) Math.floor(v), v); }
        current = "ceil_float";
        for (int i = 0; i < iters; i++) { float v = rndF(r); eq(ceil_float(v), (int) Math.ceil(v), v); }
        current = "ceil_double";
        for (int i = 0; i < iters; i++) { double v = rndD(r); eq(ceil_double(v), (int) Math.ceil(v), v); }
        current = "clamp_double";
        for (int i = 0; i < iters; i++) {
            double v = rndD(r), lo = rndD(r), hi = rndD(r);
            eq(clamp_double(v, lo, hi), v < lo ? lo : Math.min(v, hi), v, lo, hi);
        }
        current = "abs_max_float";
        for (int i = 0; i < iters; i++) {
            float a = rndF(r), b = rndF(r);
            eq(abs_max_float(a, b), Math.max(Math.abs(a), Math.abs(b)), a, b);
        }
        current = "abs_max";
        for (int i = 0; i < iters; i++) {
            double a = rndD(r), b = rndD(r);
            eq(abs_max(a, b), Math.max(Math.abs(a), Math.abs(b)), a, b);
        }
        current = "fast_inverse_cbrt";
        for (int i = 0; i < iters; i++) {
            float v = rndF(r);
            eq(fast_inverse_cbrt(v), vFastInvCbrt(v), v);
        }
        current = "hsv_to_argb";
        for (int i = 0; i < iters; i++) {
            float h = r.nextInt(4) == 0 ? rndF(r) : (float) r.nextDouble(0.0, 1.0);
            float s = (float) r.nextDouble(0.0, 1.0);
            float v = (float) r.nextDouble(0.0, 1.0);
            int alpha = r.nextInt(256);
            Integer want;
            try {
                want = vHsvToArgb(h, s, v, alpha);
            } catch (RuntimeException ex) {
                want = null;   // vanilla throws; native degrades to 0
            }
            long got = hsv_to_argb(h, s, v, alpha);
            eq(got, want == null ? 0L : (long) (int) want, h, s, v, alpha);
            if (want != null) {
                eq((long) hsv_to_rgb(h, s, v), (long) vHsvToArgb(h, s, v, 0), h, s, v);
            } else {
                samples++;
            }
        }

        perTest.forEach((k, v) -> System.out.println("  " + k + ": " + v + " mismatches"));
        System.out.println(samples + " samples, " + failures + " failures");
        System.out.println(failures == 0 ? "=== SWEEP PASS ===" : "=== SWEEP FAIL ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
