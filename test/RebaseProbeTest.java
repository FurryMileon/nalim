import one.nalim.Link;
import one.nalim.Linker;

/**
 * Regression test for the rebase relocation: adjacent RIP-relative operands
 * into writable .data (disp32 fields closer than 8 bytes) — the shape that
 * made the post-install verify pass collide with its own patches.
 * All functions must inline (their operands are non-read-only) AND return
 * exact expected values.
 */
public class RebaseProbeTest {

    @Link static native int    two_globals();     // 111 + 222
    @Link static native int    three_globals();   // + 333
    @Link static native float  float_globals();   // 1.5f * 2.25f
    @Link static native double double_globals();  // 3.5 * 0.25
    @Link static native float  mixed_globals();   // 111 * 2.25f

    public static void main(String[] args) {
        Linker.loadLibrary(new java.io.File("librebase_probe.so").getAbsolutePath());
        Linker.linkClass(RebaseProbeTest.class);

        int bad = 0;
        for (int i = 0; i < 1_000_000; i++) {
            if (two_globals() != 333) bad++;
            if (three_globals() != 666) bad++;
            if (float_globals() != 3.375f) bad++;
            if (double_globals() != 0.875) bad++;
            if (mixed_globals() != 249.75f) bad++;
        }
        System.out.println("inlined=" + Linker.getInlinedMethodCount() + "/5, bad=" + bad);
        System.out.println(bad == 0 && Linker.getInlinedMethodCount() == 5
                ? "=== REBASE PROBE PASS ===" : "=== REBASE PROBE FAIL ===");
    }
}
