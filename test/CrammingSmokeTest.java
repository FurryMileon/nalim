import com.github.tatercertified.nalim.cramming.OxidiziumCramming;
import com.github.tatercertified.nalim.rust.OxidiziumLib;
import one.nalim.Linker;

import java.util.UUID;

/**
 * End-to-end check of the cramming pipeline: Java packs synthetic mob state
 * into the OxidiziumCramming buffers, one Nalim call ships it to Rust, Rust's
 * spatial hash + push math writes deltas back, Java reads and verifies.
 *
 * Mirrors the data flow we'd see inside a real server tick:
 *   beginCollect() → addMob() × N → runBatch() → takeDelta() × N → endTick().
 *
 * No actual Minecraft entity is touched — we just exercise the FFI layer.
 */
public class CrammingSmokeTest {

    public static void main(String[] args) {
        System.out.println("java.version = " + System.getProperty("java.version"));

        // Make sure an unrelated test library cannot accidentally satisfy the
        // OxidiziumLib symbols: require the real native library explicitly.
        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "Usage: CrammingSmokeTest /absolute/path/to/liboxidizium.so");
        }
        Linker.loadLibrary(args[0]);
        Linker.linkClass(OxidiziumLib.class);
        System.out.println("Linked OxidiziumLib including cramming symbols");

        // Layout sanity — Rust's compile-time asserts already cover this on the
        // native side, but a runtime cross-check protects against an
        // accidentally-mismatched Java offset table.
        int inSize  = OxidiziumLib.oxi_cramming_input_size();
        int outSize = OxidiziumLib.oxi_cramming_output_size();
        if (inSize != OxidiziumCramming.INPUT_STRIDE)
            throw new AssertionError("INPUT_STRIDE mismatch: Java=" +
                    OxidiziumCramming.INPUT_STRIDE + " Rust=" + inSize);
        if (outSize != OxidiziumCramming.OUTPUT_STRIDE)
            throw new AssertionError("OUTPUT_STRIDE mismatch: Java=" +
                    OxidiziumCramming.OUTPUT_STRIDE + " Rust=" + outSize);
        System.out.println("Layout OK — input=" + inSize + "B, output=" + outSize + "B");

        OxidiziumCramming c = OxidiziumCramming.get();

        // -------- Case 1: two adjacent mobs push apart --------
        c.beginCollect();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        int flags = OxidiziumCramming.FLAG_CAN_PUSH | OxidiziumCramming.FLAG_IS_PUSHABLE;
        c.addMob(a, /*dim*/0, 0.0, 64.0, 0.0, 0.3, 0.95, 0.3, flags, 24);
        c.addMob(b, /*dim*/0, 0.5, 64.0, 0.0, 0.3, 0.95, 0.3, flags, 24);
        long ns = c.runBatch();
        System.out.printf("case 1 (2 mobs, overlap): %d ns%n", ns);
        double[] da = c.takeDelta(a);
        double[] db = c.takeDelta(b);
        require("a.deltaX < 0", da[0] < 0);
        require("b.deltaX > 0", db[0] > 0);
        require("|deltas| equal", Math.abs(da[0] + db[0]) < 1e-9);
        require("no Y push", da[1] == 0 && db[1] == 0);
        require("a.count == 1", c.neighbourCount(a) == 1);
        c.endTick();

        // -------- Case 2: dense cluster, 100 mobs --------
        c.beginCollect();
        UUID[] ids = new UUID[100];
        for (int i = 0; i < 100; i++) {
            ids[i] = UUID.randomUUID();
            double x = (i % 10) * 0.2;
            double z = (i / 10) * 0.2;
            c.addMob(ids[i], 0, x, 64.0, z, 0.3, 0.95, 0.3, flags, 24);
        }
        ns = c.runBatch();
        System.out.printf("case 2 (100 mobs, 2×2 area): %d ns (%.1f ns/mob)%n", ns, ns / 100.0);
        int maxCnt = 0;
        for (UUID id : ids) {
            int cnt = c.neighbourCount(id);
            if (cnt > maxCnt) maxCnt = cnt;
            require("count <= MAX_PUSHERS (24)", cnt <= 24);
        }
        System.out.printf("    max neighbour count = %d%n", maxCnt);
        c.endTick();

        // -------- Case 3: scaling test, 5000 scattered mobs (realistic peak) --------
        c.beginCollect();
        ids = new UUID[5000];
        java.util.Random rng = new java.util.Random(42);
        for (int i = 0; i < 5000; i++) {
            ids[i] = UUID.randomUUID();
            // Scatter across a 200×200 area — typical busy server world load.
            double x = rng.nextDouble() * 200.0;
            double z = rng.nextDouble() * 200.0;
            c.addMob(ids[i], 0, x, 64.0, z, 0.3, 0.95, 0.3, flags, 24);
        }
        ns = c.runBatch();
        System.out.printf("case 3 (5000 mobs, 200×200 scatter): %d ns (%.2f ns/mob)%n",
                ns, ns / 5000.0);
        c.endTick();

        // -------- Case 4: cross-dim isolation --------
        c.beginCollect();
        UUID o = UUID.randomUUID();
        UUID n = UUID.randomUUID();
        c.addMob(o, /*overworld*/0, 100.0, 64.0, 100.0, 0.3, 0.95, 0.3, flags, 24);
        c.addMob(n, /*nether*/   -1, 100.0, 64.0, 100.0, 0.3, 0.95, 0.3, flags, 24);
        c.runBatch();
        require("overworld mob has 0 neighbours from nether", c.neighbourCount(o) == 0);
        require("nether mob has 0 neighbours from overworld", c.neighbourCount(n) == 0);
        c.endTick();

        // -------- Case 5: many ticks back-to-back (steady-state perf) --------
        System.out.println("warming up steady-state perf bench...");
        for (int t = 0; t < 100; t++) {
            c.beginCollect();
            for (int i = 0; i < 1000; i++) {
                double x = rng.nextDouble() * 100;
                double z = rng.nextDouble() * 100;
                c.addMob(UUID.randomUUID(), 0, x, 64.0, z, 0.3, 0.95, 0.3, flags, 24);
            }
            c.runBatch();
            c.endTick();
        }
        long totalNs = 0;
        int reps = 1000;
        for (int t = 0; t < reps; t++) {
            c.beginCollect();
            for (int i = 0; i < 1000; i++) {
                double x = rng.nextDouble() * 100;
                double z = rng.nextDouble() * 100;
                c.addMob(UUID.randomUUID(), 0, x, 64.0, z, 0.3, 0.95, 0.3, flags, 24);
            }
            totalNs += c.runBatch();
            c.endTick();
        }
        double avgUs = (totalNs / (double) reps) / 1000.0;
        System.out.printf("case 5 (steady-state, 1000 mobs/tick × %d ticks): avg %.2f μs/tick%n",
                reps, avgUs);

        System.out.println("=== ALL PASS ===");
    }

    private static void require(String what, boolean cond) {
        if (!cond) throw new AssertionError("FAIL: " + what);
    }
}
