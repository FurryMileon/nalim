import one.nalim.Link;
import one.nalim.Linker;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;

// Linux AMD64. Load both the exact assembly fixtures and the real gcc -O2 benchmark DSO.
public class ScalarLeafTest {
    static class Bound {
        @Link static native int raw_add(int x, int y);
        @Link static native int raw_abs(int x);
        @Link static native int sl_amber_i(int x, int y);
        @Link static native long sl_amber_l(long x, long y);
        @Link static native int sl_umber_i(int x);
        @Link static native long sl_umber_l(long x);
        @Link static native int sl_ochre_i(int x, int y);
        @Link static native long sl_ochre_l(long x, long y);
        @Link static native int sl_jade_i(int x, int y);
        @Link static native long sl_jade_l(long x, long y);
        @Link static native int sl_pearl_i(int x, int y);
        @Link static native long sl_pearl_l(long x, long y);
        @Link static native int sl_indigo_i(int x, int y);
        @Link static native long sl_indigo_l(long x, long y);
        @Link static native int sl_coral_i(int x, int y);
        @Link static native long sl_coral_l(long x, long y);
        @Link static native int sl_ivory_i(int x);
        @Link static native long sl_ivory_l(long x);
        @Link static native int sl_slate_i(int x);
        @Link static native long sl_slate_l(long x);
        @Link static native int sl_ruby_i(int x);
        @Link static native long sl_ruby_l(long x);
        @Link static native int sl_late(int x, int y);
        @Link static native int sl_counted(int x, int y);
        @Link static native int sl_count();
        @Link static native int sl_page_end(int x);
        @Link static native int sl_page_cross(int x);
    }

    private static final long[] EDGES = {
            0, 1, -1, 2, -3, Integer.MIN_VALUE, Integer.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE,
            0x8000000000000001L, 0x0000000100000000L, 0x3141592676543210L, 0xfedcba9889abcdefL
    };

    private static MethodHandle intrinsic(String symbol, MethodType type) {
        MethodHandle h = Linker.findIntrinsic(symbol, type)
                .orElseThrow(() -> new AssertionError("missed " + symbol + type));
        if (!h.type().equals(type)) throw new AssertionError(symbol + ": " + h.type());
        return h;
    }

    private static void equal(String symbol, long actual, long expected) {
        if (actual != expected) throw new AssertionError(symbol + ": got " + actual + ", expected " + expected);
    }

    private static void unary(String name, Class<?> word, LongUnaryOperator op) throws Throwable {
        MethodType sig = MethodType.methodType(word, word);
        MethodHandle h = intrinsic(name, sig);
        MethodHandle linked = MethodHandles.lookup().findStatic(Bound.class, name, sig);
        for (long x : EDGES) {
            if (word == int.class) {
                int i = (int) x, expected = (int) op.applyAsLong(i);
                equal(name, (int) h.invokeExact(i), expected);
                equal(name, (int) linked.invokeExact(i), expected);
            } else {
                long expected = op.applyAsLong(x);
                equal(name, (long) h.invokeExact(x), expected);
                equal(name, (long) linked.invokeExact(x), expected);
            }
        }
    }

    private static void binary(String name, Class<?> word, LongBinaryOperator op) throws Throwable {
        MethodType sig = MethodType.methodType(word, word, word);
        MethodHandle h = intrinsic(name, sig);
        MethodHandle linked = MethodHandles.lookup().findStatic(Bound.class, name, sig);
        for (long x : EDGES) for (long y : EDGES) {
            if (word == int.class) {
                int i = (int) x, j = (int) y, expected = (int) op.applyAsLong(i, j);
                equal(name, (int) h.invokeExact(i, j), expected);
                equal(name, (int) linked.invokeExact(i, j), expected);
            } else {
                long expected = op.applyAsLong(x, y);
                equal(name, (long) h.invokeExact(x, y), expected);
                equal(name, (long) linked.invokeExact(x, y), expected);
            }
        }
    }

    private static void widths(String stem, LongUnaryOperator op) throws Throwable {
        unary(stem + "_i", int.class, op);
        unary(stem + "_l", long.class, op);
    }

    private static void widths(String stem, LongBinaryOperator op) throws Throwable {
        binary(stem + "_i", int.class, op);
        binary(stem + "_l", long.class, op);
    }

    private static void absent(String name, Class<?> result, Class<?>... args) {
        MethodType sig = MethodType.methodType(result, args);
        if (Linker.findIntrinsic(name, sig).isPresent()) throw new AssertionError("false match " + name + sig);
    }

    public static void main(String[] args) throws Throwable {
        Linker.loadLibrary("nalim_scalar_leaves");
        Linker.loadLibrary("nalim_bench");
        Linker.linkClass(Bound.class);
        if (Boolean.getBoolean("nalim.noinline")) {
            absent("raw_add", int.class, int.class, int.class);
            absent("raw_abs", int.class, int.class);
            equal("noinline add", Bound.raw_add(Integer.MAX_VALUE, 1), Integer.MIN_VALUE);
            equal("noinline abs", Bound.raw_abs(-17), 17);
            System.out.println("ScalarLeafTest fallback PASS");
            return;
        }

        binary("raw_add", int.class, (x, y) -> x + y);
        unary("raw_abs", int.class, Math::abs);
        equal("ret at page end", Linker.findAddress("sl_page_end") & 4095, 4093);
        unary("sl_page_end", int.class, x -> x);
        equal("ret on next page", Linker.findAddress("sl_page_cross") & 4095, 4094);
        absent("sl_page_cross", int.class, int.class);
        equal("cross-page fallback", Bound.sl_page_cross(-17), -17);
        widths("sl_amber", (x, y) -> x + y);
        widths("sl_umber", x -> Math.abs(x));
        widths("sl_ochre", (x, y) -> x - y);
        widths("sl_jade", (x, y) -> x * y);
        widths("sl_pearl", (x, y) -> x & y);
        widths("sl_indigo", (x, y) -> x | y);
        widths("sl_coral", (x, y) -> x ^ y);
        widths("sl_ivory", x -> -x);
        widths("sl_slate", x -> ~x);
        widths("sl_ruby", x -> x);

        for (String name : new String[]{"sl_read", "sl_scale", "sl_late", "sl_counted"}) {
            absent(name, int.class, int.class, int.class);
        }
        for (String name : new String[]{"sl_select", "sl_damage", "sl_trap", "sl_double", "sl_mixed", "sl_extend"}) {
            absent(name, int.class, int.class);
            absent(name, long.class, long.class);
        }
        absent("raw_add", int.class, int.class);
        absent("raw_add", long.class, long.class, long.class);
        absent("raw_add", int.class, long.class, int.class);
        absent("raw_add", int.class, int.class, long.class);
        absent("raw_add", long.class, int.class, int.class);
        absent("raw_add", int.class, int.class, int.class, int.class);
        absent("raw_abs", int.class);
        absent("raw_abs", void.class, int.class);
        absent("raw_abs", byte.class, byte.class);
        absent("raw_abs", boolean.class, boolean.class);
        absent("raw_abs", float.class, float.class);
        absent("raw_abs", Integer.class, Integer.class);
        absent("sl_umber_l", int.class, int.class);
        absent("sl_nonexistent_scalar", int.class, int.class);
        if (Linker.findAddress("sl_signature_bytes") == 0) throw new AssertionError("missing data fixture");
        absent("sl_signature_bytes", int.class, int.class);

        // A failed match must leave the original effects intact on the normal @Link path.
        equal("late result", Bound.sl_late(3, 5), 27);
        int before = Bound.sl_count();
        equal("counted result", Bound.sl_counted(7, -5), 2);
        equal("side effect", Bound.sl_count(), before + 1);
        System.out.println("ScalarLeafTest PASS");
    }
}
