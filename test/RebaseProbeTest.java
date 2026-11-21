import one.nalim.Link;
import one.nalim.Linker;

/** Loads from writable native globals must stay live after the methods are linked. */
public class RebaseProbeTest {

    @Link static native int    two_globals();     // 111 + 222
    @Link static native int    three_globals();   // + 333
    @Link static native float  float_globals();   // 1.5f * 2.25f
    @Link static native double double_globals();  // 3.5 * 0.25
    @Link static native float  mixed_globals();   // 111 * 2.25f
    @Link static native void poke(int a, float b, double c);

    private static void check(int two, int three, float floats, double doubles, float mixed) {
        if (two_globals() != two || three_globals() != three || float_globals() != floats
                || double_globals() != doubles || mixed_globals() != mixed) {
            throw new AssertionError("native global values did not match");
        }
    }

    public static void main(String[] args) {
        Linker.loadLibrary(new java.io.File("librebase_probe.so").getAbsolutePath());
        int before = Linker.getInlinedMethodCount();
        Linker.linkClass(RebaseProbeTest.class);

        if (Linker.getInlinedMethodCount() != before) {
            throw new AssertionError("method accessing writable globals was inlined");
        }
        check(333, 666, 3.375f, 0.875, 249.75f);
        poke(1, 0.5f, 0.25);
        check(335, 669, 5.5f, 1.875, 308.0f);
        System.out.println("=== REBASE PROBE PASS ===");
    }
}
