import one.nalim.Library;
import one.nalim.Link;
import one.nalim.Linker;
import one.nalim.ModuleOpener;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;

// RNG head-to-head: Java's three RNGs (Random LCG, SplittableRandom
// SplitMix64, ThreadLocalRandom) against Rust's SplitMix64 + WyRand via
// Nalim. Same operations across all (nextInt bounded / nextLong /
// nextFloat / nextDouble / nextGaussian) so cost differences are
// real algorithmic / FFI differences, not workload mismatch.
//
// State for Rust RNGs lives in a 32-byte DirectByteBuffer (layout:
// offset 0  = u64 state
// offset 8  = u8  has_stash (Gaussian)
// offset 16 = f64 stashed gaussian sample).

@Library("bench_rng_rust")
public class BenchRng {

    @Link static native int  rust_splitmix_next_int(long stateAddr);
    @Link static native int  rust_splitmix_next_int_bounded(long stateAddr, int bound);
    @Link static native long rust_splitmix_next_long(long stateAddr);
    @Link static native float rust_splitmix_next_float(long stateAddr);
    @Link static native double rust_splitmix_next_double(long stateAddr);
    @Link static native double rust_splitmix_next_gaussian(long stateAddr);
    @Link static native int  rust_wyrand_next_int_bounded(long stateAddr, int bound);
    @Link static native long rust_wyrand_next_long(long stateAddr);
    @Link static native float rust_wyrand_next_float(long stateAddr);
    @Link static native int  rust_splitmix_fill_int_bounded(long stateAddr, long outAddr, int count, int bound);
    @Link static native long rust_mint_seed();

    static final int N = 50_000_000;
    static final int WARMUP = 2_000_000;

    public static void main(String[] args) throws Exception {
        System.out.println("java.version = " + System.getProperty("java.version"));
        ModuleOpener.opensPackage("java.base", "java.nio");
        Linker.linkClass(BenchRng.class);

        // 32-byte off-heap state for Rust side
        ByteBuffer stateBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.LITTLE_ENDIAN);
        long stateAddr = directAddress(stateBuf);
        // seed
        stateBuf.putLong(0, 0x123456789ABCDEF0L);

        // 16 KB output buffer for bulk fill
        ByteBuffer outBuf = ByteBuffer.allocateDirect(16 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        long outAddr = directAddress(outBuf);

        // Java RNG instances
        Random javaRandom = new Random(0xABCDEF0123456789L);
        SplittableRandom splitRandom = new SplittableRandom(0xABCDEF0123456789L);
        ThreadLocalRandom tlr = ThreadLocalRandom.current();

        // sanity correctness — print one sample from each
        System.out.println();
        System.out.printf("Random.nextInt(100)             = %d%n", javaRandom.nextInt(100));
        System.out.printf("SplittableRandom.nextInt(100)   = %d%n", splitRandom.nextInt(100));
        System.out.printf("ThreadLocalRandom.nextInt(100)  = %d%n", tlr.nextInt(100));
        System.out.printf("Rust splitmix nextInt(100)      = %d%n", rust_splitmix_next_int_bounded(stateAddr, 100));
        System.out.printf("Rust wyrand   nextInt(100)      = %d%n", rust_wyrand_next_int_bounded(stateAddr, 100));
        System.out.printf("Rust splitmix nextGaussian      = %f%n", rust_splitmix_next_gaussian(stateAddr));

        // warmup all variants
        long sink = 0;
        for (int i = 0; i < WARMUP; i++) {
            sink += javaRandom.nextInt(100);
            sink += splitRandom.nextInt(100);
            sink += tlr.nextInt(100);
            sink += rust_splitmix_next_int_bounded(stateAddr, 100);
            sink += rust_wyrand_next_int_bounded(stateAddr, 100);
        }
        if (sink == 0) System.out.println("never");

        System.out.println();
        System.out.println("=== nextInt(100) bounded ===");
        bench("Random.nextInt(100)              ", () -> javaRandom.nextInt(100));
        bench("SplittableRandom.nextInt(100)    ", () -> splitRandom.nextInt(100));
        bench("ThreadLocalRandom.nextInt(100)   ", () -> tlr.nextInt(100));
        bench("Rust splitmix nextInt(100)       ", () -> rust_splitmix_next_int_bounded(stateAddr, 100));
        bench("Rust wyrand   nextInt(100)       ", () -> rust_wyrand_next_int_bounded(stateAddr, 100));

        System.out.println();
        System.out.println("=== nextLong ===");
        benchL("Random.nextLong()                ", javaRandom::nextLong);
        benchL("SplittableRandom.nextLong()      ", splitRandom::nextLong);
        benchL("ThreadLocalRandom.nextLong()     ", tlr::nextLong);
        benchL("Rust splitmix nextLong()         ", () -> rust_splitmix_next_long(stateAddr));
        benchL("Rust wyrand   nextLong()         ", () -> rust_wyrand_next_long(stateAddr));

        System.out.println();
        System.out.println("=== nextFloat ===");
        benchF("Random.nextFloat()               ", javaRandom::nextFloat);
        benchF("SplittableRandom (via nextLong)  ", () -> (splitRandom.nextLong() >>> 40) * (1.0f / (1 << 24)));
        benchF("ThreadLocalRandom.nextFloat()    ", tlr::nextFloat);
        benchF("Rust splitmix nextFloat()        ", () -> rust_splitmix_next_float(stateAddr));
        benchF("Rust wyrand   nextFloat()        ", () -> rust_wyrand_next_float(stateAddr));

        System.out.println();
        System.out.println("=== nextDouble ===");
        benchD("Random.nextDouble()              ", javaRandom::nextDouble);
        benchD("SplittableRandom.nextDouble()    ", splitRandom::nextDouble);
        benchD("ThreadLocalRandom.nextDouble()   ", tlr::nextDouble);
        benchD("Rust splitmix nextDouble()       ", () -> rust_splitmix_next_double(stateAddr));

        System.out.println();
        System.out.println("=== nextGaussian (the expensive one) ===");
        benchD("Random.nextGaussian()            ", javaRandom::nextGaussian);
        benchD("SplittableRandom.nextGaussian()  ", splitRandom::nextGaussian);
        benchD("ThreadLocalRandom.nextGaussian() ", tlr::nextGaussian);
        benchD("Rust splitmix nextGaussian       ", () -> rust_splitmix_next_gaussian(stateAddr));

        System.out.println();
        System.out.println("=== bulk fill: 1024 nextInt(100) into off-heap array ===");
        int[] javaOut = new int[1024];
        benchBulk("Java Random fill 1024 ints       ", () -> {
            for (int i = 0; i < 1024; i++) javaOut[i] = javaRandom.nextInt(100);
            return javaOut[1023];
        });
        benchBulk("Java SplittableRandom fill 1024  ", () -> {
            for (int i = 0; i < 1024; i++) javaOut[i] = splitRandom.nextInt(100);
            return javaOut[1023];
        });
        benchBulk("Java ThreadLocalRandom fill 1024 ", () -> {
            for (int i = 0; i < 1024; i++) javaOut[i] = tlr.nextInt(100);
            return javaOut[1023];
        });
        benchBulk("Rust splitmix fill 1024 (single call)", () ->
                rust_splitmix_fill_int_bounded(stateAddr, outAddr, 1024, 100));
        benchBulk("Rust splitmix per-call ×1024     ", () -> {
            int last = 0;
            for (int i = 0; i < 1024; i++) last = rust_splitmix_next_int_bounded(stateAddr, 100);
            return last;
        });

        System.out.println();
        System.out.println("=== bulk fill: 16 nextInt(100) into off-heap array (entity-tick scale) ===");
        benchBulk("Java Random fill 16              ", () -> {
            int last = 0;
            for (int i = 0; i < 16; i++) last = javaRandom.nextInt(100);
            return last;
        });
        benchBulk("Java SplittableRandom fill 16    ", () -> {
            int last = 0;
            for (int i = 0; i < 16; i++) last = splitRandom.nextInt(100);
            return last;
        });
        benchBulk("Rust splitmix fill 16 (single call)", () ->
                rust_splitmix_fill_int_bounded(stateAddr, outAddr, 16, 100));
        benchBulk("Rust splitmix per-call ×16       ", () -> {
            int last = 0;
            for (int i = 0; i < 16; i++) last = rust_splitmix_next_int_bounded(stateAddr, 100);
            return last;
        });
    }

    interface IntCall  { int    call(); }
    interface LongCall { long   call(); }
    interface FloatCall { float call(); }
    interface DoubleCall { double call(); }

    static void bench(String name, IntCall fn) {
        double best = Double.MAX_VALUE; int sink = 0;
        for (int r = 0; r < 5; r++) {
            int t = 0; long a = System.nanoTime();
            for (int i = 0; i < N; i++) t += fn.call();
            long b = System.nanoTime();
            sink = t;
            double ns = (b - a) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-38s : %6.3f ns/op (sink=%d)%n", name, best, sink);
    }
    static void benchL(String name, LongCall fn) {
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            long t = 0; long a = System.nanoTime();
            for (int i = 0; i < N; i++) t += fn.call();
            long b = System.nanoTime();
            sink = t;
            double ns = (b - a) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-38s : %6.3f ns/op (sink=%d)%n", name, best, sink);
    }
    static void benchF(String name, FloatCall fn) {
        double best = Double.MAX_VALUE; float sink = 0;
        for (int r = 0; r < 5; r++) {
            float t = 0; long a = System.nanoTime();
            for (int i = 0; i < N; i++) t += fn.call();
            long b = System.nanoTime();
            sink = t;
            double ns = (b - a) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-38s : %6.3f ns/op (sink=%f)%n", name, best, sink);
    }
    static void benchD(String name, DoubleCall fn) {
        double best = Double.MAX_VALUE; double sink = 0;
        for (int r = 0; r < 5; r++) {
            double t = 0; long a = System.nanoTime();
            for (int i = 0; i < N; i++) t += fn.call();
            long b = System.nanoTime();
            sink = t;
            double ns = (b - a) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-38s : %6.3f ns/op (sink=%f)%n", name, best, sink);
    }

    // For bulk: fewer iterations because each call is heavier.
    static final int BULK_N = 200_000;
    static void benchBulk(String name, IntCall fn) {
        double best = Double.MAX_VALUE; int sink = 0;
        for (int r = 0; r < 5; r++) {
            int t = 0; long a = System.nanoTime();
            for (int i = 0; i < BULK_N; i++) t ^= fn.call();
            long b = System.nanoTime();
            sink = t;
            double ns = (b - a) / (double) BULK_N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-38s : %9.1f ns/op (sink=%d)%n", name, best, sink);
    }

    static long directAddress(ByteBuffer buf) throws Exception {
        Field f = java.nio.Buffer.class.getDeclaredField("address");
        f.setAccessible(true);
        return f.getLong(buf);
    }
}
