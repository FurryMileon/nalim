import one.nalim.Link;
import one.nalim.Linker;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.IntToLongFunction;

// Call throughput on the RustMi ABI shapes, not a measurement of its kernels or server TPS.
public final class BenchRustMiAbi {
    @Link(name = "abi_collide") private static native int collide(long header, long solids, long out);
    @Link(name = "abi_spawncap_can_local") private static native int spawncap(long handle, int cat, int x, int z);
    @Link(name = "abi_distance_sort") private static native int distance(long xs, long ys, long zs,
            double tx, double ty, double tz, int len, long out);
    @Link private static native long take8(long a, long b, long c, long d, long e, long f, long g, long h);

    private static final long A = 0x1472_8933_2656_3400L;
    private static final long B = 0x8eb5_afe6_cf37_2800L;
    private static final long C = 0x407a_5580_2f7b_0800L;
    private static final long D = 0xe174_d8c0_a400_1f00L;
    private static final long WIDE = 0x1_0000_0000L;
    private static final int PERIOD = 512;
    private static final int CALLS = PERIOD * 24_576;

    private static int fold(long x) {
        return (int) (x ^ (x >>> 32));
    }

    private static long collisionLoop(int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int v = (i & (PERIOD - 1)) - PERIOD / 2;
            sum += collide(A + v, B - v, C + 2L * v);
        }
        return sum;
    }

    private static long spawncapLoop(int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int v = (i & (PERIOD - 1)) - PERIOD / 2;
            sum += spawncap(A - v, i & 7, v, 19 - 3 * v);
        }
        return sum;
    }

    private static long distanceLoop(int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int v = (i & (PERIOD - 1)) - PERIOD / 2;
            sum += distance(A + v, B - v, C + 2L * v, v + 0.25, -0.5 * v - 2.5,
                    7.75 - v, i & 63, D - v);
        }
        return sum;
    }

    private static long stackLoop(int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int v = (i & (PERIOD - 1)) - PERIOD / 2;
            sum += take8(v, 4 - v, 2L * v, 17, -3L * v, 1_000 - v, WIDE + 5L * v, -WIDE - v);
        }
        return sum;
    }

    private static void check(String name, long expected, long actual) {
        if (actual != expected) throw new AssertionError(name + ": expected " + expected + ", got " + actual);
    }

    private static void measure(String name, IntToLongFunction loop, long perPeriod) {
        for (int i = 0; i < 5; i++) check(name, perPeriod * 6_144, loop.applyAsLong(CALLS / 4));
        long[] times = new long[7];
        for (int i = 0; i < times.length; i++) {
            long start = System.nanoTime();
            long checksum = loop.applyAsLong(CALLS);
            times[i] = System.nanoTime() - start;
            check(name, perPeriod * (CALLS / PERIOD), checksum);
        }
        Arrays.sort(times);
        System.out.printf(Locale.ROOT, "%s=%.4f%n", name, times[times.length / 2] / (double) CALLS);
    }

    public static void main(String[] args) {
        Linker.loadLibrary("rustmi_abi_fixtures");
        Linker.linkClass(BenchRustMiAbi.class);
        long collisionSum = 0, spawncapSum = 0, distanceSum = 0, stackSum = 0;
        for (int i = 0; i < PERIOD; i++) {
            int v = i - PERIOD / 2;
            int collision = fold(A + v) + 3 * fold(B - v) + 5 * fold(C + 2L * v);
            int spawn = fold(A - v) + 3 * (i & 7) - 26 * v + 133;
            // Exact binary fractions: 13*(v+.25) + 17*(-v/2-2.5) - 19*(7.75-v).
            int coords = (47 * v - 373) / 2;
            int sort = collision + 7 * coords + 11 * (i & 63) + 23 * fold(D - v);
            // The weighted sum in take8 reduces to this affine function of v.
            long stack = -4 * WIDE + 17_207 + 28L * v;
            check("collision args " + i, collision, collide(A + v, B - v, C + 2L * v));
            check("spawncap args " + i, spawn, spawncap(A - v, i & 7, v, 19 - 3 * v));
            check("mixed args " + i, sort, distance(A + v, B - v, C + 2L * v,
                    v + 0.25, -0.5 * v - 2.5, 7.75 - v, i & 63, D - v));
            check("stack args " + i, stack, take8(v, 4 - v, 2L * v, 17, -3L * v,
                    1_000 - v, WIDE + 5L * v, -WIDE - v));
            collisionSum += collision;
            spawncapSum += spawn;
            distanceSum += sort;
            stackSum += stack;
        }
        measure("collide_abi_ns", BenchRustMiAbi::collisionLoop, collisionSum);
        measure("spawncap_abi_ns", BenchRustMiAbi::spawncapLoop, spawncapSum);
        measure("distance_abi_ns", BenchRustMiAbi::distanceLoop, distanceSum);
        measure("stack_abi_ns", BenchRustMiAbi::stackLoop, stackSum);
    }
}
