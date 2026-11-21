import one.nalim.Code;
import one.nalim.Linker;

import java.util.function.IntSupplier;

// AMD64: mov eax, imm32; ret. The immediates make every decoded nibble observable.
public class CodeLiteralTest {
    static class Valid {
        @Code("b878563412c3") static native int contiguous();

        // ASCII \s, including whitespace between nibbles and both signs and digits.
        // Fullwidth B/A, Arabic-Indic 8 and fullwidth 5 are also radix-16 digits.
        @Code(" \t\uFF22\n\u0668 +\013F -\fF \uFF21\r\uFF15 0 0 C\t3\n")
        static native int mixed();
    }

    // A valid return before each error keeps the failure check safe even if a partial
    // prefix were incorrectly installed before the exception was thrown.
    static class Invalid {
        @Code("b84d000000c3z00") static native int odd();
        @Code("b84d000000c3\u00A00") static native int nonAsciiSpace();
        @Code("b84d000000c30-") static native int misplacedSign();
    }

    private static void reject(String name, Class<? extends IllegalArgumentException> error,
                               IntSupplier call) throws Exception {
        try {
            Linker.linkMethod(Invalid.class.getDeclaredMethod(name));
            throw new AssertionError(name + " linked");
        } catch (IllegalArgumentException e) {
            if (e.getClass() != error) throw new AssertionError(name + ": wrong exception", e);
        }
        try {
            throw new AssertionError(name + " was installed and returned " + call.getAsInt());
        } catch (UnsatisfiedLinkError expected) {
            // The native method must still be unresolved.
        }
    }

    private static int compiledCaller() {
        int sum = 0;
        for (int i = 0; i < 256; i++) sum += Valid.contiguous();
        return sum;
    }

    public static void main(String[] args) throws Exception {
        String arch = System.getProperty("os.arch");
        if (!"amd64".equals(arch) && !"x86_64".equals(arch)) {
            throw new UnsupportedOperationException("AMD64 fixture");
        }
        Linker.linkClass(Valid.class);
        if (Valid.contiguous() != 0x12345678 || Valid.mixed() != 0x00a5f10f) {
            throw new AssertionError("wrong @Code immediate");
        }
        // Odd length wins even if a preceding pair also has an invalid digit.
        reject("odd", IllegalArgumentException.class, Invalid::odd);
        reject("nonAsciiSpace", NumberFormatException.class, Invalid::nonAsciiSpace);
        reject("misplacedSign", NumberFormatException.class, Invalid::misplacedSign);
        // A compiled Java caller must follow the replacement nmethod after
        // the original entry becomes non-entrant, including across a GC.
        for (int i = 0; i < 2_000; i++) {
            if (compiledCaller() != 0x12345678 * 256) throw new AssertionError("warm call");
        }
        System.gc();
        try {
            Linker.installCode(Valid.class.getDeclaredMethod("contiguous"), new byte[1], Integer.MAX_VALUE - 95);
            throw new AssertionError("oversized source length accepted");
        } catch (IndexOutOfBoundsException expected) {
            if (compiledCaller() != 0x12345678 * 256) throw new AssertionError("invalid relink changed body");
        }
        Linker.installCode(Valid.class.getDeclaredMethod("contiguous"),
                new byte[]{(byte) 0xb8, 77, 0, 0, 0, (byte) 0xc3});
        System.gc();
        if (compiledCaller() != 77 * 256) throw new AssertionError("stale @Code body after relink");
        System.out.println("CodeLiteralTest OK");
    }
}
