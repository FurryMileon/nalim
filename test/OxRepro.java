import one.nalim.Library;
import one.nalim.Link;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.SplittableRandom;

import static java.lang.foreign.ValueLayout.*;

// Reproducer for the Oxidizium-reported inconsistencies:
//   Major: positiveCeilDiv (off by 1), roundTowards (completely off)
//   Minor: wrapDegrees90, atan2, sin (~1e-16)
// Strategy: call the SAME liboxidizium.so function via Nalim (JVMCI) and via
// Panama FFM. Any bit-difference is a Nalim codegen bug by construction.
@Library("oxidizium")
public class OxRepro {

    @Link static native int    positive_ceil_div_int (int input, int divisor);
    @Link static native long   positive_ceil_div_long(long input, long divisor);
    @Link static native int    round_towards_int     (int input, int multiple);
    @Link static native long   round_towards_long    (long input, long multiple);
    @Link static native int    floor_div             (int dividend, int divisor);
    @Link static native int    ceil_div              (int a, int b);
    @Link static native int    round_up_to_multiple  (int value, int divisor);
    @Link static native float  wrap_degrees_90       (float angle);
    @Link static native float  wrap_degrees_float    (float degrees);
    @Link static native double wrap_degrees_double   (double degrees);
    @Link static native int    wrap_degrees_int      (int degrees);
    @Link static native float  wrap_degrees_long     (long degrees);
    @Link static native double atan_2                (double y, double x);
    @Link static native float  sin_float             (float x);
    @Link static native float  sin_double            (double x);
    @Link static native float  cos_float             (float x);
    @Link static native float  lithium_sin_float     (float x);
    @Link static native int    floor_float           (float x);
    @Link static native double magnitude_int         (int a, double b, int c);
    @Link static native int    round_down_to_multiple(double a, int b);
    @Link static native int    clamp_int             (int v, int lo, int hi);
    @Link static native int    abs_int               (int x);
    @Link static native boolean is_power_of_2        (long value);
    @Link static native boolean is_power_of_two      (int value);
    @Link static native boolean approximately_equals_float (float a, float b);
    @Link static native boolean approximately_equals_double(double a, double b);

    static MethodHandle p_ceil_i, p_ceil_l, p_round_i, p_round_l, p_floor_div, p_ceil_div,
            p_round_up, p_wrap90, p_wrapf, p_wrapd, p_wrapi, p_wrapl, p_atan2, p_sinf,
            p_sind, p_cosf, p_lsinf, p_floorf, p_magi, p_rdown, p_clampi, p_absi,
            p_pow2l, p_pow2i, p_aeqf, p_aeqd;

    static int failures = 0;

    public static void main(String[] args) throws Throwable {
        System.out.println("java.version=" + System.getProperty("java.version")
                + " noinline=" + Boolean.getBoolean("nalim.noinline")
                + " nodirect=" + Boolean.getBoolean("nalim.nodirect"));

        one.nalim.Linker.linkClass(OxRepro.class);
        try {
            System.out.println("inlined=" + one.nalim.Linker.class.getMethod("getInlinedMethodCount").invoke(null)
                    + " direct=" + one.nalim.Linker.class.getMethod("getDirectJumpMethodCount").invoke(null));
        } catch (ReflectiveOperationException e) {
            System.out.println("(old nalim: no inline/direct counters)");
        }

        SymbolLookup lib = SymbolLookup.libraryLookup(
                java.nio.file.Path.of(System.getProperty("oxlib.path", "test/liboxidizium.so"))
                        .toAbsolutePath(), Arena.global());
        java.lang.foreign.Linker ln = java.lang.foreign.Linker.nativeLinker();

        p_ceil_i  = dh(ln, lib, "positive_ceil_div_int",  FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
        p_ceil_l  = dh(ln, lib, "positive_ceil_div_long", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG));
        p_round_i = dh(ln, lib, "round_towards_int",      FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
        p_round_l = dh(ln, lib, "round_towards_long",     FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG));
        p_floor_div = dh(ln, lib, "floor_div",            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
        p_ceil_div  = dh(ln, lib, "ceil_div",             FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
        p_round_up  = dh(ln, lib, "round_up_to_multiple", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
        p_wrap90  = dh(ln, lib, "wrap_degrees_90",     FunctionDescriptor.of(JAVA_FLOAT, JAVA_FLOAT));
        p_wrapf   = dh(ln, lib, "wrap_degrees_float",  FunctionDescriptor.of(JAVA_FLOAT, JAVA_FLOAT));
        p_wrapd   = dh(ln, lib, "wrap_degrees_double", FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE));
        p_wrapi   = dh(ln, lib, "wrap_degrees_int",    FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        p_wrapl   = dh(ln, lib, "wrap_degrees_long",   FunctionDescriptor.of(JAVA_FLOAT, JAVA_LONG));
        p_atan2   = dh(ln, lib, "atan_2",              FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
        p_sinf    = dh(ln, lib, "sin_float",           FunctionDescriptor.of(JAVA_FLOAT, JAVA_FLOAT));
        p_sind    = dh(ln, lib, "sin_double",          FunctionDescriptor.of(JAVA_FLOAT, JAVA_DOUBLE));
        p_cosf    = dh(ln, lib, "cos_float",           FunctionDescriptor.of(JAVA_FLOAT, JAVA_FLOAT));
        p_lsinf   = dh(ln, lib, "lithium_sin_float",   FunctionDescriptor.of(JAVA_FLOAT, JAVA_FLOAT));
        p_floorf  = dh(ln, lib, "floor_float",         FunctionDescriptor.of(JAVA_INT, JAVA_FLOAT));
        p_magi    = dh(ln, lib, "magnitude_int",       FunctionDescriptor.of(JAVA_DOUBLE, JAVA_INT, JAVA_DOUBLE, JAVA_INT));
        p_rdown   = dh(ln, lib, "round_down_to_multiple", FunctionDescriptor.of(JAVA_INT, JAVA_DOUBLE, JAVA_INT));
        p_clampi  = dh(ln, lib, "clamp_int",           FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        p_absi    = dh(ln, lib, "abs_int",             FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        p_pow2l   = dh(ln, lib, "is_power_of_2",       FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_LONG));
        p_pow2i   = dh(ln, lib, "is_power_of_two",     FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_INT));
        p_aeqf    = dh(ln, lib, "approximately_equals_float",  FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_FLOAT, JAVA_FLOAT));
        p_aeqd    = dh(ln, lib, "approximately_equals_double", FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_DOUBLE, JAVA_DOUBLE));

        SplittableRandom r = new SplittableRandom(0xC0FFEE);
        int N = 200_000;

        for (int i = 0; i < N; i++) {
            int a = smallOrWide(r), b = nonZero(r);
            long la = r.nextLong(), lb = nonZeroL(r);
            if (a == Integer.MIN_VALUE) a++;           // avoid Rust neg-overflow panic
            if (la == Long.MIN_VALUE) la++;
            if (b == -1 && a == Integer.MIN_VALUE + 1) b = 2;

            cmpI("positive_ceil_div_int", positive_ceil_div_int(a, b), (int) p_ceil_i.invokeExact(a, b), a, b);
            cmpL("positive_ceil_div_long", positive_ceil_div_long(la, lb), (long) p_ceil_l.invokeExact(la, lb), la, lb);
            cmpI("round_towards_int", round_towards_int(a, b), (int) p_round_i.invokeExact(a, b), a, b);
            cmpL("round_towards_long", round_towards_long(la, lb), (long) p_round_l.invokeExact(la, lb), la, lb);
            cmpI("floor_div", floor_div(a, b), (int) p_floor_div.invokeExact(a, b), a, b);
            cmpI("ceil_div", ceil_div(a, b), (int) p_ceil_div.invokeExact(a, b), a, b);
            cmpI("round_up_to_multiple", round_up_to_multiple(a, b), (int) p_round_up.invokeExact(a, b), a, b);
            cmpI("clamp_int", clamp_int(a, -1000, 1000), (int) p_clampi.invokeExact(a, -1000, 1000), a, 0);
            cmpI("abs_int", abs_int(a == Integer.MIN_VALUE ? 1 : a), (int) p_absi.invokeExact(a == Integer.MIN_VALUE ? 1 : a), a, 0);

            float f = (r.nextFloat() - 0.5f) * 10000f;
            double d = (r.nextDouble() - 0.5) * 10000;
            cmpF("wrap_degrees_90", wrap_degrees_90(f), (float) p_wrap90.invokeExact(f), f);
            cmpF("wrap_degrees_float", wrap_degrees_float(f), (float) p_wrapf.invokeExact(f), f);
            cmpD("wrap_degrees_double", wrap_degrees_double(d), (double) p_wrapd.invokeExact(d), d);
            cmpI("wrap_degrees_int", wrap_degrees_int(a), (int) p_wrapi.invokeExact(a), a, 0);
            cmpF("wrap_degrees_long", wrap_degrees_long(la), (float) p_wrapl.invokeExact(la), la);
            cmpD("atan_2", atan_2(d, d * 0.7 + 1), (double) p_atan2.invokeExact(d, d * 0.7 + 1), d);
            cmpF("sin_float", sin_float(f), (float) p_sinf.invokeExact(f), f);
            cmpF("sin_double", sin_double(d), (float) p_sind.invokeExact(d), d);
            cmpF("cos_float", cos_float(f), (float) p_cosf.invokeExact(f), f);
            cmpF("lithium_sin_float", lithium_sin_float(f), (float) p_lsinf.invokeExact(f), f);
            cmpI("floor_float", floor_float(f), (int) p_floorf.invokeExact(f), Float.floatToRawIntBits(f), 0);
            cmpD("magnitude_int", magnitude_int(a % 1000, d, (a >> 3) % 1000),
                    (double) p_magi.invokeExact(a % 1000, d, (a >> 3) % 1000), d);
            cmpI("round_down_to_multiple", round_down_to_multiple(d, b), (int) p_rdown.invokeExact(d, b), (int) d, b);

            cmpB("is_power_of_2", is_power_of_2(la), (boolean) p_pow2l.invokeExact(la), la);
            cmpB("is_power_of_2/pow", is_power_of_2(1L << (i & 62)), (boolean) p_pow2l.invokeExact(1L << (i & 62)), 1L << (i & 62));
            cmpB("is_power_of_two", is_power_of_two(a), (boolean) p_pow2i.invokeExact(a), a);
            cmpB("approximately_equals_float", approximately_equals_float(f, f * 1.0000001f),
                    (boolean) p_aeqf.invokeExact(f, f * 1.0000001f), Float.floatToRawIntBits(f));
            cmpB("approximately_equals_double", approximately_equals_double(d, d + 1e-9),
                    (boolean) p_aeqd.invokeExact(d, d + 1e-9), (long) d);
        }

        if (failures == 0) {
            System.out.println("=== ALL MATCH (" + N + " iterations, bit-exact vs Panama) ===");
        } else {
            System.out.println("=== " + failures + " FAILURES ===");
            System.exit(1);
        }
    }

    static MethodHandle dh(java.lang.foreign.Linker ln, SymbolLookup lib, String name, FunctionDescriptor fd) {
        MemorySegment addr = lib.find(name).orElseThrow(() -> new IllegalStateException(name));
        return ln.downcallHandle(addr, fd);
    }

    static int smallOrWide(SplittableRandom r) {
        return r.nextBoolean() ? r.nextInt(-2000, 2000) : r.nextInt();
    }
    static int nonZero(SplittableRandom r) {
        int v = r.nextBoolean() ? r.nextInt(-64, 64) : r.nextInt();
        return v == 0 ? 7 : v;
    }
    static long nonZeroL(SplittableRandom r) {
        long v = r.nextBoolean() ? r.nextLong(-64, 64) : r.nextLong();
        return v == 0 ? 7 : v;
    }

    static void cmpI(String what, int nalim, int panama, long a, long b) {
        if (nalim != panama && failures++ < 20) {
            System.out.printf("MISMATCH %s(%d, %d): nalim=%d panama=%d%n", what, a, b, nalim, panama);
        }
    }
    static void cmpL(String what, long nalim, long panama, long a, long b) {
        if (nalim != panama && failures++ < 20) {
            System.out.printf("MISMATCH %s(%d, %d): nalim=%d panama=%d%n", what, a, b, nalim, panama);
        }
    }
    static void cmpB(String what, boolean nalim, boolean panama, long in) {
        if (nalim != panama && failures++ < 20) {
            System.out.printf("MISMATCH %s(%d): nalim=%s panama=%s%n", what, in, nalim, panama);
        }
    }

    static void cmpF(String what, float nalim, float panama, double in) {
        if (Float.floatToRawIntBits(nalim) != Float.floatToRawIntBits(panama) && failures++ < 20) {
            System.out.printf("MISMATCH %s(%s): nalim=%s panama=%s%n", what, in, nalim, panama);
        }
    }
    static void cmpD(String what, double nalim, double panama, double in) {
        if (Double.doubleToRawLongBits(nalim) != Double.doubleToRawLongBits(panama) && failures++ < 20) {
            System.out.printf("MISMATCH %s(%s): nalim=%s panama=%s%n", what, in, nalim, panama);
        }
    }
}
