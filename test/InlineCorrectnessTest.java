import one.nalim.Library;
import one.nalim.Link;
import one.nalim.Linker;
import one.nalim.ModuleOpener;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// Correctness gate for the extended auto-inliner. Every Rust RNG below is
// mirrored bit-exactly in Java and compared over millions of iterations.
// If the x86-64 length decoder ever mis-decodes an instruction (wrong length
// -> desynced copy -> garbage code), or the RIP literal relocation writes a
// wrong disp32, this fails loudly or crashes — both are "loud".
//
// Run with -Dnalim.trace.inline=true to see per-symbol inline decisions.
@Library("bench_rng_rust")
public class InlineCorrectnessTest {

    @Link static native long   rust_splitmix_next_long(long stateAddr);
    @Link static native int    rust_splitmix_next_int(long stateAddr);
    @Link static native int    rust_splitmix_next_int_bounded(long stateAddr, int bound);
    @Link static native float  rust_splitmix_next_float(long stateAddr);
    @Link static native double rust_splitmix_next_double(long stateAddr);
    @Link static native long   rust_wyrand_next_long(long stateAddr);
    @Link static native float  rust_wyrand_next_float(long stateAddr);
    @Link static native double rust_splitmix_next_gaussian(long stateAddr); // branches -> stub path

    static final int N = 2_000_000;

    public static void main(String[] args) throws Exception {
        System.out.println("java.version = " + System.getProperty("java.version"));
        ModuleOpener.opensPackage("java.base", "java.nio");
        Linker.linkClass(InlineCorrectnessTest.class);
        System.out.println("inlined methods: " + Linker.getInlinedMethodCount());

        ByteBuffer stateBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.LITTLE_ENDIAN);
        long stateAddr = directAddress(stateBuf);

        // ── splitmix64: long / int / bounded / float / double ──────────
        long seed = 0x123456789ABCDEF0L;
        stateBuf.putLong(0, seed);
        long jState = seed;
        for (int i = 0; i < N; i++) {
            long rust = rust_splitmix_next_long(stateAddr);
            jState += 0x9E3779B97F4A7C15L;
            long java = mix(jState);
            if (rust != java) throw new AssertionError("next_long diverged at i=" + i + ": " + rust + " != " + java);
        }
        check(stateBuf.getLong(0), jState, "state after next_long");

        for (int i = 0; i < N; i++) {
            int rust = rust_splitmix_next_int(stateAddr);
            jState += 0x9E3779B97F4A7C15L;
            int java = (int) (mix(jState) >>> 32);
            if (rust != java) throw new AssertionError("next_int diverged at i=" + i);
        }

        int[] bounds = {1, 2, 3, 7, 100, 256, 1_000_000, Integer.MAX_VALUE};
        for (int i = 0; i < N; i++) {
            int bound = bounds[i & 7];
            int rust = rust_splitmix_next_int_bounded(stateAddr, bound);
            jState += 0x9E3779B97F4A7C15L;
            long v = mix(jState);
            int java = (int) Math.unsignedMultiplyHigh(v, Integer.toUnsignedLong(bound));
            if (rust != java) throw new AssertionError("bounded diverged at i=" + i + " bound=" + bound);
        }

        for (int i = 0; i < N; i++) {
            float rust = rust_splitmix_next_float(stateAddr);
            jState += 0x9E3779B97F4A7C15L;
            float java = (float) (mix(jState) >>> 40) * (1.0f / (1 << 24));
            if (Float.floatToRawIntBits(rust) != Float.floatToRawIntBits(java)) {
                throw new AssertionError("next_float diverged at i=" + i + ": " + rust + " != " + java);
            }
        }

        for (int i = 0; i < N; i++) {
            double rust = rust_splitmix_next_double(stateAddr);
            jState += 0x9E3779B97F4A7C15L;
            double java = (double) (mix(jState) >>> 11) * (1.0 / (1L << 53));
            if (Double.doubleToRawLongBits(rust) != Double.doubleToRawLongBits(java)) {
                throw new AssertionError("next_double diverged at i=" + i);
            }
        }

        // ── wyrand: long / float ───────────────────────────────────────
        stateBuf.putLong(0, seed);
        jState = seed;
        for (int i = 0; i < N; i++) {
            long rust = rust_wyrand_next_long(stateAddr);
            jState += 0xA0761D6478BD642FL;
            long java = wyMix(jState);
            if (rust != java) throw new AssertionError("wyrand_long diverged at i=" + i);
        }

        for (int i = 0; i < N; i++) {
            float rust = rust_wyrand_next_float(stateAddr);
            jState += 0xA0761D6478BD642FL;
            float java = (float) (wyMix(jState) >>> 40) * (1.0f / (1 << 24));
            if (Float.floatToRawIntBits(rust) != Float.floatToRawIntBits(java)) {
                throw new AssertionError("wyrand_float diverged at i=" + i);
            }
        }

        // ── gaussian goes through the stub path (has branches) — sanity only ──
        stateBuf.putLong(0, seed);
        stateBuf.putLong(8, 0);
        double gSum = 0;
        for (int i = 0; i < 100_000; i++) {
            double g = rust_splitmix_next_gaussian(stateAddr);
            if (Double.isNaN(g) || Math.abs(g) > 10.0) throw new AssertionError("gaussian bogus: " + g);
            gSum += g;
        }
        double gMean = gSum / 100_000;
        if (Math.abs(gMean) > 0.02) throw new AssertionError("gaussian mean off: " + gMean);

        int inlined = Linker.getInlinedMethodCount();
        System.out.println("inlined methods after link: " + inlined + " of 8 (gaussian must stay stub)");
        if (inlined < 7) {
            throw new AssertionError("expected 7 inlined bodies (all but gaussian), got " + inlined);
        }
        System.out.println("=== PASS === (" + N + " iterations x 7 variants, bit-exact)");
    }

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static long wyMix(long s) {
        long b = s ^ 0xE7037ED1A0B428DBL;
        long hi = Math.unsignedMultiplyHigh(s, b);
        long lo = s * b;
        return hi ^ lo;
    }

    static void check(long actual, long expected, String what) {
        if (actual != expected) {
            throw new AssertionError(what + ": " + actual + " != " + expected);
        }
    }

    static long directAddress(ByteBuffer buf) throws Exception {
        Field f = java.nio.Buffer.class.getDeclaredField("address");
        f.setAccessible(true);
        return f.getLong(buf);
    }
}
