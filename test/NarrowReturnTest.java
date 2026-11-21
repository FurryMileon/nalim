import one.nalim.Link;
import one.nalim.Linker;

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

        @Link(name = "nalim_narrow_mixed")
        static native short mixed(
                int i1, int i2, int i3, int i4, int i5, int i6,
                double d1, double d2, double d3, double d4, double d5, double d6, double d7, double d8,
                int i7, float f1, long l1, double d9, int i8, float f2, long l2, double d10,
                int i9, float f3, long l3, double d11, int i10, float f4, long l4, double d12);
    }

    public static void main(String[] args) {
        String library = args.length == 0
                ? "test/libnalim_narrow_returns.so"
                : args[0];
        Linker.loadLibrary(new java.io.File(library).getAbsolutePath());
        Linker.linkClass(RegisterOnly.class);
        Linker.linkClass(StackArguments.class);

        for (int i = 0; i < 1_000_000; i++) {
            check(!RegisterOnly.boolFalse(), "dirty false became true");
            check(RegisterOnly.boolTrue(), "dirty true became false");
            check(RegisterOnly.byteValue() == (byte) 0x80, "byte was not sign-extended");
            check(RegisterOnly.shortValue() == (short) 0x8001, "short was not sign-extended");
            check(RegisterOnly.charValue() == '\uabcd', "char was not zero-extended");
            check(RegisterOnly.stackAligned(), "native call stack is misaligned");
            sink ^= RegisterOnly.byteValue();

            int value = (i & 255) - 128;
            check(StackArguments.seventhLong(1, 2, 3, 4, 5, 6, value) == value,
                    "seventh GPR argument moved");
            check(StackArguments.ninthDouble(1, 2, 3, 4, 5, 6, 7, 8, value) == value,
                    "ninth XMM argument moved");
            check(StackArguments.mixed(1, 2, 3, 4, 5, 6,
                    1.5, 2.5, 3.5, 4.5, 5.5, 6.5, 7.5, 8.5,
                    -7, -1.25f, 0x100000001L, 9.5, -8, -2.25f, 0x200000002L, 10.5,
                    -9, -3.25f, 0x300000003L, 11.5, -10, -4.25f, 0x400000004L, 12.5) == -12345,
                    "mixed register or stack argument moved");
        }

        System.out.printf(
                "NARROW_RETURN_OK noinline=%s direct=%d inline=%d sink=%d%n",
                Boolean.getBoolean("nalim.noinline"),
                Linker.getDirectJumpMethodCount(),
                Linker.getInlinedMethodCount(),
                sink);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
