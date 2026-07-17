import one.nalim.Link;
import one.nalim.Linker;

import java.lang.reflect.Method;

public final class NarrowReturnTest {
    private static volatile int sink;

    private static final class RegisterOnly {
        @Link(name = "nalim_dirty_bool_false")
        static native boolean boolFalse();

        @Link(name = "nalim_dirty_bool_true")
        static native boolean boolTrue();

        @Link(name = "nalim_dirty_byte")
        static native byte byteValue();

        @Link(name = "nalim_dirty_short")
        static native short shortValue();

        @Link(name = "nalim_dirty_char")
        static native char charValue();

        @Link(name = "nalim_stack_is_aligned")
        static native boolean stackAligned();
    }

    private static final class StackArguments {
        @Link(name = "nalim_narrow_stack7")
        static native byte seventhLong(
                long a, long b, long c, long d, long e, long f, long seventh);

        @Link(name = "nalim_narrow_stack9d")
        static native byte ninthDouble(
                double a, double b, double c, double d, double e,
                double f, double g, double h, double ninth);
    }

    public static void main(String[] args) throws Exception {
        String library = args.length == 0
                ? "test/libnalim_narrow_returns.so"
                : args[0];
        Linker.loadLibrary(new java.io.File(library).getAbsolutePath());
        Linker.linkClass(RegisterOnly.class);

        for (int i = 0; i < 1_000_000; i++) {
            check(!RegisterOnly.boolFalse(), "dirty false became true");
            check(RegisterOnly.boolTrue(), "dirty true became false");
            check(RegisterOnly.byteValue() == (byte) 0x80, "byte was not sign-extended");
            check(RegisterOnly.shortValue() == (short) 0x8001, "short was not sign-extended");
            check(RegisterOnly.charValue() == '\uabcd', "char was not zero-extended");
            check(RegisterOnly.stackAligned(), "native call stack is misaligned");
            sink ^= RegisterOnly.byteValue();
        }

        boolean noInline = Boolean.getBoolean("nalim.noinline");
        if (noInline) {
            expectStackArgumentRejection("seventhLong", long.class, long.class,
                    long.class, long.class, long.class, long.class, long.class);
            expectStackArgumentRejection("ninthDouble", double.class, double.class,
                    double.class, double.class, double.class, double.class,
                    double.class, double.class, double.class);
        } else {
            Linker.linkClass(StackArguments.class);
            check(StackArguments.seventhLong(1, 2, 3, 4, 5, 6, -128) == -128,
                    "seventh GPR argument moved");
            check(StackArguments.ninthDouble(1, 2, 3, 4, 5, 6, 7, 8, -42) == -42,
                    "ninth XMM argument moved");
        }

        System.out.printf(
                "NARROW_RETURN_OK noinline=%s direct=%d inline=%d sink=%d%n",
                noInline,
                Linker.getDirectJumpMethodCount(),
                Linker.getInlinedMethodCount(),
                sink);
    }

    private static void expectStackArgumentRejection(
            String name, Class<?>... parameterTypes) throws Exception {
        Method method = StackArguments.class.getDeclaredMethod(name, parameterTypes);
        try {
            Linker.linkMethod(method);
            throw new AssertionError("Expected stack-argument rejection for " + name);
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("stack arguments"),
                    "unexpected rejection: " + expected);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
