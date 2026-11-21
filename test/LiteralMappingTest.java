import one.nalim.Linker;

import java.nio.file.Path;

/** Linux AMD64: two ordinary bindings of the same load, without a library load between them. */
public final class LiteralMappingTest {
    static native int literal_page_writable(int writable);
    static native void literal_page_store(int value);
    static native int first();
    static native int second();

    public static void main(String[] args) throws Exception {
        Linker.loadLibrary(Path.of(args[0]).toAbsolutePath().toString());
        Linker.linkMethod(LiteralMappingTest.class.getDeclaredMethod("literal_page_writable", int.class));
        Linker.linkMethod(LiteralMappingTest.class.getDeclaredMethod("literal_page_store", int.class));
        if (literal_page_writable(0) != 0) throw new AssertionError("cannot protect fixture data page");
        try {
            int before = Linker.getInlinedMethodCount();
            Linker.linkMethod(LiteralMappingTest.class.getDeclaredMethod("first"), "literal_page_load", false);
            if (first() != 11 || Linker.getInlinedMethodCount() != before + 1) {
                throw new AssertionError("read-only load did not inline");
            }
        } finally {
            if (literal_page_writable(1) != 0) throw new AssertionError("cannot restore fixture data page");
        }
        // Never call the first (constant) binding after ending its literal's lifetime.
        Linker.linkMethod(LiteralMappingTest.class.getDeclaredMethod("second"), "literal_page_load", false);
        literal_page_store(42);
        if (second() != 42) throw new AssertionError("second binding froze a writable literal");
        System.out.println("LiteralMappingTest PASS");
    }
}
