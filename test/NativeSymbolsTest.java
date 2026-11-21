import one.nalim.Linker;

// Run in a fresh JVM with test/nalim_bench.c built as libnalim_bench.
public class NativeSymbolsTest {
    public static void main(String[] args) {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        // The context loader must not affect which native libraries are visible.
        thread.setContextClassLoader(null);
        try {
            if (Linker.findAddress("raw_add") != 0) {
                throw new AssertionError("raw_add found before loading nalim_bench");
            }
            // Loading outside of Linker must still make a previous miss visible.
            System.loadLibrary("nalim_bench");
            long add = Linker.findAddress("raw_add");
            long abs = Linker.findAddress("raw_abs");
            if (add == 0 || abs == 0 || add == abs) {
                throw new AssertionError("Expected distinct raw_add and raw_abs addresses");
            }
            if (Linker.findAddress("RAW_ADD") != 0 || Linker.findAddress("raw_ad") != 0) {
                throw new AssertionError("An inexact symbol name resolved");
            }
            if (Linker.findAddress("raw_add") != add) {
                throw new AssertionError("Repeated lookup changed the raw_add address");
            }
        } finally {
            thread.setContextClassLoader(original);
        }
        System.out.println("NativeSymbolsTest passed");
    }
}
