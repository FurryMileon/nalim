import one.nalim.Library;
import one.nalim.Link;
import one.nalim.Linker;
import one.nalim.ModuleOpener;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.SplittableRandom;

// Decomposes the "FFI vs C2-inline" gap into its components, to answer:
// how much would hacking C2 (true inlining of foreign code) actually buy?
//
//   A) SplittableRandom.nextLong()        — C2 full inline, JDK impl (reference)
//   B) hand-written Java splitmix loop    — C2 full inline, identical code shape
//                                           = the ceiling a perfect C2 hack could reach
//   C) s += GAMMA in Java; rust_splitmix_mix(s) via Nalim
//                                         — state in a Java REGISTER, pure FFI call
//                                           (body auto-inlined into nmethod, no jmp)
//   D) rust_splitmix_next_long(stateAddr) — state in native memory (round-trips L1)
//
//   C − B = irreducible call + ret + caller-saved spill cost.
//           This is the ONLY part that a C2 hack could still remove.
//   D − C = cost of keeping RNG state in memory instead of a register.
//           Removable TODAY by API design (pure functions), no hacks.
@Library("bench_rng_rust")
public class BenchPureCall {

    @Link static native long rust_splitmix_mix(long z);
    @Link static native long rust_wyrand_mix(long s);
    @Link static native long rust_splitmix_next_long(long stateAddr);

    static final long GAMMA = 0x9E3779B97F4A7C15L;
    static final int N = 50_000_000;

    public static void main(String[] args) throws Exception {
        System.out.println("java.version = " + System.getProperty("java.version"));
        ModuleOpener.opensPackage("java.base", "java.nio");
        Linker.linkClass(BenchPureCall.class);

        ByteBuffer stateBuf = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN);
        long stateAddr = directAddress(stateBuf);
        stateBuf.putLong(0, 0x123456789ABCDEF0L);

        SplittableRandom sr = new SplittableRandom(0x123456789ABCDEF0L);

        // correctness: C must equal a Java-mixed sequence
        long s = 42;
        for (int i = 0; i < 1000; i++) {
            s += GAMMA;
            long rust = rust_splitmix_mix(s);
            long java = javaMix(s);
            if (rust != java) throw new AssertionError("mix diverged at " + i);
        }

        // warmup
        long sink = 0;
        long ws = 1;
        for (int i = 0; i < 2_000_000; i++) {
            sink += sr.nextLong();
            ws += GAMMA; sink += javaMix(ws);
            ws += GAMMA; sink += rust_splitmix_mix(ws);
            ws += GAMMA; sink += rust_wyrand_mix(ws);
            sink += rust_splitmix_next_long(stateAddr);
        }
        if (sink == 0) System.out.println("never");

        System.out.println();
        benchSR ("A) SplittableRandom.nextLong  [C2 inline]   ", sr);
        benchJav("B) Java splitmix loop         [C2 inline]   ");
        benchMix("C) Java state + Rust mix      [pure FFI]    ");
        benchWy ("C') Java state + Rust wyMix   [pure FFI]    ");
        benchMem("D) Rust state-in-memory       [current FFI] ", stateAddr);
        System.out.println();
        System.out.println("C-B = irreducible call cost (only a C2 hack removes this)");
        System.out.println("D-C = memory round-trip of state (removable by API design)");
    }

    static long javaMix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static void benchSR(String name, SplittableRandom sr) {
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            long t = 0, a = System.nanoTime();
            for (int i = 0; i < N; i++) t += sr.nextLong();
            best = Math.min(best, (System.nanoTime() - a) / (double) N);
            sink = t;
        }
        report(name, best, sink);
    }

    static void benchJav(String name) {
        double best = Double.MAX_VALUE; long sink = 0;
        long s = 0x123456789ABCDEF0L;
        for (int r = 0; r < 5; r++) {
            long t = 0, a = System.nanoTime();
            for (int i = 0; i < N; i++) { s += GAMMA; t += javaMix(s); }
            best = Math.min(best, (System.nanoTime() - a) / (double) N);
            sink = t;
        }
        report(name, best, sink);
    }

    static void benchMix(String name) {
        double best = Double.MAX_VALUE; long sink = 0;
        long s = 0x123456789ABCDEF0L;
        for (int r = 0; r < 5; r++) {
            long t = 0, a = System.nanoTime();
            for (int i = 0; i < N; i++) { s += GAMMA; t += rust_splitmix_mix(s); }
            best = Math.min(best, (System.nanoTime() - a) / (double) N);
            sink = t;
        }
        report(name, best, sink);
    }

    static void benchWy(String name) {
        double best = Double.MAX_VALUE; long sink = 0;
        long s = 0x123456789ABCDEF0L;
        for (int r = 0; r < 5; r++) {
            long t = 0, a = System.nanoTime();
            for (int i = 0; i < N; i++) { s += 0xA0761D6478BD642FL; t += rust_wyrand_mix(s); }
            best = Math.min(best, (System.nanoTime() - a) / (double) N);
            sink = t;
        }
        report(name, best, sink);
    }

    static void benchMem(String name, long stateAddr) {
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            long t = 0, a = System.nanoTime();
            for (int i = 0; i < N; i++) t += rust_splitmix_next_long(stateAddr);
            best = Math.min(best, (System.nanoTime() - a) / (double) N);
            sink = t;
        }
        report(name, best, sink);
    }

    static void report(String name, double ns, long sink) {
        System.out.printf("  %-46s : %6.3f ns/op (sink=%d)%n", name, ns, sink);
    }

    static long directAddress(ByteBuffer buf) throws Exception {
        Field f = java.nio.Buffer.class.getDeclaredField("address");
        f.setAccessible(true);
        return f.getLong(buf);
    }
}
