import one.nalim.Code;
import one.nalim.FieldOffset;
import one.nalim.Link;
import one.nalim.Linker;

import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.HexFormat;

public class PlatformAbiTest {
    private static final String ARCH = System.getProperty("os.arch");
    private static final boolean AMD64 = ARCH.equals("amd64") || ARCH.equals("x86_64");

    static class Box {
        long untouched = 0x123456789abcdefL;
        long selected = 100;
    }

    @Link static native int abi_xor(int x, int y);
    @Link static native double abi_memory(int[] array, @FieldOffset("selected") Box box, double scale);
    // Fixed prototype (not variadic). After eight GP and eight FP arguments,
    // Darwin arm64 packs q/r/s adjacently and then aligns the remaining spills.
    @Link static native int abi_mixed(
            long a, float b, long c, double d, long e, float f, long g, double h,
            long i, float j, long k, double l, long m, float n, long o, double p,
            byte q, byte r, short s, float t, float u, double v, long w);

    // Sub-word return normalization is a documented AMD64 feature.
    static class Narrow {
        @Link static native boolean abi_bool(int x);
        @Link static native byte abi_byte(int x);
        @Link static native short abi_short(int x);
        @Link static native char abi_char(int x);

        @Link(naked = true) static native short abi_java_regs(
                int a, double b, int c, double d, int e, double f, int g, double h,
                int fifth, double j, int sixth, double l, double m, double eighthFp);
        @Link(naked = true) static native short abi_java_spills(
                int a, int b, int c, int d, int e, int f,
                float g, float h, float i, float j, float k, float l, float m, float n,
                int seventh, float ninthFp);
    }

    static class X64 { @Code("b8 29 00 00 00 c3") static native int value(); }
    static class Arm { @Code("20 05 80 52 c0 03 5f d6") static native int value(); }

    private static int machineCall() {
        return AMD64 ? X64.value() : Arm.value();
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void checkAbi() {
        int mask = abi_mixed(
                0x100000001L, 2.25f, 0x300000003L, -4.5, 0x500000005L, 6.25f, 0x700000007L, -8.5,
                0x900000009L, 10.25f, 0xb0000000bL, -12.5, 0xd0000000dL, 14.25f, 0xf0000000fL, -16.5,
                (byte)-73, (byte)91, (short)-12345, -20.25f, 21.25f, -22.5, 0x1700000017L);
        if (mask != 0) throw new AssertionError("bad mixed arguments, bit mask: 0x" + Integer.toHexString(mask));
        check(abi_xor(0x12345678, 0x87654321) == 0x95511559, "scalar result");
        int[] array = {12, -7, 42};
        Box box = new Box();
        check(abi_memory(array, box, 0.5) == 52.5, "floating return");
        check(array[0] == 12 && array[1] == 5 && array[2] == 42, "array base");
        check(box.selected == 105 && box.untouched == 0x123456789abcdefL, "field offset");
        if (AMD64) {
            check(!Narrow.abi_bool(0) && Narrow.abi_bool(2), "boolean return");
            check(Narrow.abi_byte(-113) == -113, "byte return");
            check(Narrow.abi_short(-30001) == -30001, "short return");
            check(Narrow.abi_char(0xfedc) == 0xfedc, "char return");
            check(Narrow.abi_java_regs(1, 2, 3, 4, 5, 6, 7, 8, 77, 10, -130, 12, 13, 8.5) == -199,
                    "naked register-only short return");
            check(Narrow.abi_java_spills(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 384, -47.5f) == -431,
                    "naked spilled short return");
        }
    }

    public static void main(String[] args) throws Exception {
        check(AMD64 || ARCH.equals("aarch64") || ARCH.equals("arm64"), "unsupported fixture ISA: " + ARCH);
        Linker.loadLibrary("platform_abi");
        Linker.linkClass(PlatformAbiTest.class);
        if (AMD64) Linker.linkClass(Narrow.class);
        if (!(AMD64 && System.getProperty("os.name").equals("Linux"))) {
            check(Linker.findIntrinsic("abi_xor", MethodType.methodType(int.class, int.class, int.class)).isEmpty(),
                    "Linux AMD64 scalar intrinsic used outside its ABI");
        }
        Method method = (AMD64 ? X64.class : Arm.class).getDeclaredMethod("value");
        Linker.linkMethod(method);
        // check-platform.sh keeps machineCall out of line and compiles synchronously.
        for (int i = 0; i < 10000; i++) {
            check(machineCall() == 41, "initial @Code");
            checkAbi();
        }
        System.gc();
        check(machineCall() == 41, "@Code after GC");
        // mov eax,73; ret / mov w0,#73; ret.
        Linker.installCode(method, HexFormat.of().parseHex(AMD64 ? "b849000000c3" : "20098052c0035fd6"));
        check(machineCall() == 73, "compiled caller did not follow relink");
        System.gc();
        check(machineCall() == 73, "relinked @Code after GC");
        checkAbi();
        System.out.println("PlatformAbiTest passed: " + System.getProperty("os.name") + " " + ARCH);
    }
}
