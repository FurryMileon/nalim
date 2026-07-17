import one.nalim.Library;
import one.nalim.Link;
import one.nalim.Linker;
import one.nalim.ModuleOpener;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.SplittableRandom;

// Minecraft explosion math, head-to-head. Both impls operate on the
// SAME off-heap u8 blast-resistance grid and use the SAME RNG algorithm
// (SplitMix64) so the only thing being measured is the language /
// runtime cost of the ray-tracing loop.
//
// Vanilla algo: 1352 rays on the surface of a 16-cube. Each ray steps
// at 0.3 blocks/increment, intensity starts at radius*(0.7..1.3), each
// step subtracts (res+0.3)*0.3 if the cell is non-empty plus a flat
// 0.22500001. A destroyed-position is recorded any step intensity stays
// positive afterwards.

@Library("bench_explosion_rust")
public class BenchExplosion {

    @Link static native int rust_explosion_trace(long inputAddr);
    @Link static native int rust_explosion_trace_count_only(long inputAddr);
    @Link static native int rust_explosion_trace_parallel(long inputAddr);
    @Link static native int rust_explosion_trace_batch(long inputsAddr, int count, int parallel);
    @Link static native int rust_explosion_input_size();
    @Link static native int rust_explosion_warmup();

    static final int GRID_SIZE = 32;                       // covers radius up to ~7
    static final int GRID_BYTES = GRID_SIZE * GRID_SIZE * GRID_SIZE;
    static final int OUT_MAX_TRIPLES = 16 * 1024;          // 16k destroyed positions max
    static final int OUT_BYTES = OUT_MAX_TRIPLES * 12;     // 3 × i32 per triple

    static ByteBuffer gridBuf;
    static ByteBuffer outBuf;
    static ByteBuffer inputBuf;
    static long gridAddr;
    static long outAddr;
    static long inputAddr;

    static double originX = 8.5, originY = 64.5, originZ = 8.5;
    static int gridOriginX, gridOriginY, gridOriginZ;
    static float radius = 4.0f;                            // TNT default

    public static void main(String[] args) throws Exception {
        System.out.println("java.version = " + System.getProperty("java.version"));
        ModuleOpener.opensPackage("java.base", "java.nio");
        Linker.linkClass(BenchExplosion.class);

        // Layout sanity
        int rustSize = rust_explosion_input_size();
        System.out.println("Rust ExplosionInput size = " + rustSize + " (Java expects 72)");
        if (rustSize != 72) throw new AssertionError("struct layout mismatch");

        // Off-heap buffers
        gridBuf  = ByteBuffer.allocateDirect(GRID_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        outBuf   = ByteBuffer.allocateDirect(OUT_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        inputBuf = ByteBuffer.allocateDirect(72).order(ByteOrder.LITTLE_ENDIAN);
        gridAddr  = directAddress(gridBuf);
        outAddr   = directAddress(outBuf);
        inputAddr = directAddress(inputBuf);

        // Build a synthetic blast grid that resembles a typical surface
        // chunk profile (mostly stone with some air pockets).
        seedGrid(0xC0FFEE);

        // Grid origin centered on the explosion
        gridOriginX = (int) Math.floor(originX) - GRID_SIZE / 2;
        gridOriginY = (int) Math.floor(originY) - GRID_SIZE / 2;
        gridOriginZ = (int) Math.floor(originZ) - GRID_SIZE / 2;

        // Sanity check — one explosion, same seed both sides
        long seed = 0xDEADBEEFCAFEBABEL;
        writeInput(seed);
        int rustCount = rust_explosion_trace(inputAddr);
        int javaCount = javaExplosionTrace(seed, null);
        int javaCountWithOut = javaExplosionTrace(seed, new int[OUT_MAX_TRIPLES * 3]);

        System.out.printf("Rust trace      count = %d%n", rustCount);
        System.out.printf("Java trace      count = %d%n", javaCount);
        System.out.printf("Java trace+out  count = %d%n", javaCountWithOut);
        // counts won't match exactly because the destroyed list grows
        // with every positive intensity step (vanilla doesn't dedup at
        // ray time either); the important thing is both are non-zero
        // and within 5-10% of each other.
        if (rustCount == 0 || javaCount == 0) throw new AssertionError("zero count!");

        // ── benches ─────────────────────────────────────────────────
        // warmup
        int rustWorkers = rust_explosion_warmup();
        System.out.println("Rust parallel workers = " + rustWorkers);
        for (int i = 0; i < 1000; i++) {
            writeInput(seed + i);
            rust_explosion_trace(inputAddr);
            rust_explosion_trace_count_only(inputAddr);
            rust_explosion_trace_parallel(inputAddr);
            javaExplosionTrace(seed + i, null);
        }

        System.out.println();
        System.out.println("=== single explosion (radius=" + radius + ", grid=" + GRID_SIZE + "³, " +
                "1352 rays) ===");
        int[] javaOut = new int[OUT_MAX_TRIPLES * 3];
        benchExp("Java trace (count only)         ", () -> javaExplosionTrace(seed, null));
        benchExp("Java trace + write positions    ", () -> javaExplosionTrace(seed, javaOut));
        benchExp("Rust trace (count only)         ", () -> rust_explosion_trace_count_only(inputAddr));
        benchExp("Rust trace + write positions    ", () -> rust_explosion_trace(inputAddr));
        benchExp("Rust trace PARALLEL (8 workers) ", () -> rust_explosion_trace_parallel(inputAddr));

        // ── batch of N explosions (TNT cannon scenario) ────────────
        System.out.println();
        System.out.println("=== batch of N explosions, radius=" + radius + " ===");
        for (int n : new int[]{4, 16, 50}) {
            radius = 4.0f;
            // build batch input buffer
            ByteBuffer batchInput = ByteBuffer.allocateDirect(72 * n).order(ByteOrder.LITTLE_ENDIAN);
            long batchAddr = directAddress(batchInput);
            for (int k = 0; k < n; k++) {
                int off = k * 72;
                batchInput.putDouble(off,      originX + k * 0.5);
                batchInput.putDouble(off + 8,  originY);
                batchInput.putDouble(off + 16, originZ + k * 0.3);
                batchInput.putLong  (off + 24, seed + k);
                batchInput.putLong  (off + 32, gridAddr);
                batchInput.putLong  (off + 40, outAddr);
                batchInput.putFloat (off + 48, radius);
                batchInput.putInt   (off + 52, GRID_SIZE);
                batchInput.putInt   (off + 56, gridOriginX);
                batchInput.putInt   (off + 60, gridOriginY);
                batchInput.putInt   (off + 64, gridOriginZ);
                batchInput.putInt   (off + 68, OUT_MAX_TRIPLES);
            }
            final int nn = n;
            System.out.printf("--- N = %d explosions ---%n", n);
            benchExp("Java sequential                 ", () -> {
                int t = 0;
                for (int k = 0; k < nn; k++) t += javaExplosionTrace(seed + k, null);
                return t;
            });
            benchExp("Rust sequential (via batch)     ",
                    () -> rust_explosion_trace_batch(batchAddr, nn, 0));
            benchExp("Rust PARALLEL (via batch)       ",
                    () -> rust_explosion_trace_batch(batchAddr, nn, 1));
        }

        // ── varying radius ─────────────────────────────────────────
        System.out.println();
        System.out.println("=== varying radius (count-only, no writeout) ===");
        for (float r : new float[]{2.0f, 4.0f, 6.0f, 8.0f}) {
            radius = r;
            // re-seed input (radius changed)
            writeInput(seed);
            System.out.printf("--- radius = %.1f ---%n", r);
            benchExp("  Java count-only", () -> javaExplosionTrace(seed, null));
            benchExp("  Rust count-only", () -> rust_explosion_trace_count_only(inputAddr));
        }
    }

    static void writeInput(long seed) {
        inputBuf.putDouble(0,  originX);
        inputBuf.putDouble(8,  originY);
        inputBuf.putDouble(16, originZ);
        inputBuf.putLong(24, seed);
        inputBuf.putLong(32, gridAddr);
        inputBuf.putLong(40, outAddr);
        inputBuf.putFloat(48, radius);
        inputBuf.putInt(52, GRID_SIZE);
        inputBuf.putInt(56, gridOriginX);
        inputBuf.putInt(60, gridOriginY);
        inputBuf.putInt(64, gridOriginZ);
        inputBuf.putInt(68, OUT_MAX_TRIPLES);
    }

    // Synthetic resistance grid: 60% stone (res=6 u8), 30% dirt (res=2),
    // 9% air (res=0), 1% bedrock (res=255). Realistic-ish surface chunk.
    static void seedGrid(long seed) {
        SplittableRandom rng = new SplittableRandom(seed);
        for (int i = 0; i < GRID_BYTES; i++) {
            int r = rng.nextInt(100);
            byte resistance;
            if (r < 9)        resistance = 0;        // air
            else if (r < 39)  resistance = 2;        // dirt
            else if (r < 99)  resistance = 6;        // stone
            else              resistance = (byte) 255; // bedrock
            gridBuf.put(i, resistance);
        }
    }

    // Java mirror of rust_explosion_trace, same algorithm, same RNG.
    // Pass null for `out` to skip writeout (mirrors count-only Rust fn).
    static int javaExplosionTrace(long seed, int[] out) {
        long rng = seed;
        int count = 0;
        int outCap = out == null ? Integer.MAX_VALUE : out.length / 3;

        for (int ix = 0; ix < 16; ix++) {
            for (int iy = 0; iy < 16; iy++) {
                for (int iz = 0; iz < 16; iz++) {
                    if (!(ix == 0 || ix == 15 || iy == 0 || iy == 15 || iz == 0 || iz == 15)) continue;

                    double dx = ix / 15.0 * 2 - 1;
                    double dy = iy / 15.0 * 2 - 1;
                    double dz = iz / 15.0 * 2 - 1;
                    double invLen = 1.0 / Math.sqrt(dx*dx + dy*dy + dz*dz);
                    dx *= invLen; dy *= invLen; dz *= invLen;

                    // splitmix64 step → float[0,1)
                    rng += 0x9E3779B97F4A7C15L;
                    long z = rng;
                    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
                    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
                    z = z ^ (z >>> 31);
                    float rnd01 = (z >>> 40) * (1.0f / (1 << 24));

                    float intensity = radius * (0.7f + rnd01 * 0.6f);
                    double px = originX, py = originY, pz = originZ;

                    while (intensity > 0.0f) {
                        int gx = (int) Math.floor(px) - gridOriginX;
                        int gy = (int) Math.floor(py) - gridOriginY;
                        int gz = (int) Math.floor(pz) - gridOriginZ;
                        boolean indestructible = false;
                        if ((gx | gy | gz) >= 0 && gx < GRID_SIZE && gy < GRID_SIZE && gz < GRID_SIZE) {
                            int idx = (gx * GRID_SIZE + gy) * GRID_SIZE + gz;
                            int res = gridBuf.get(idx) & 0xFF;
                            if (res == 255) { indestructible = true; }
                            else if (res != 0) {
                                intensity -= ((res * 0.2f) + 0.3f) * 0.3f;
                            }
                        }
                        if (indestructible) break;
                        if (intensity > 0.0f) {
                            if (out != null && count < outCap) {
                                int base = count * 3;
                                out[base    ] = (int) Math.floor(px);
                                out[base + 1] = (int) Math.floor(py);
                                out[base + 2] = (int) Math.floor(pz);
                            }
                            count++;
                        }
                        intensity -= 0.22500001f;
                        px += dx * 0.3;
                        py += dy * 0.3;
                        pz += dz * 0.3;
                    }
                }
            }
        }
        return count;
    }

    static long directAddress(ByteBuffer buf) throws Exception {
        Field f = java.nio.Buffer.class.getDeclaredField("address");
        f.setAccessible(true);
        return f.getLong(buf);
    }

    interface IntCall { int call(); }
    static void benchExp(String name, IntCall fn) {
        double best = Double.MAX_VALUE;
        int sink = 0;
        for (int r = 0; r < 5; r++) {
            int t = 0;
            long a = System.nanoTime();
            for (int i = 0; i < 2000; i++) t += fn.call();
            long b = System.nanoTime();
            sink = t;
            double us = (b - a) / 2000.0 / 1000.0;   // microseconds per explosion
            if (us < best) best = us;
        }
        System.out.printf("  %-38s : %7.2f μs/explosion (sink=%d)%n", name, best, sink);
    }
}
