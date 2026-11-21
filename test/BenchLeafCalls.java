import one.nalim.Link;
import one.nalim.Linker;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.SplittableRandom;

// Identical bytecode on old/new jars. Reflection only discovers the optional API
// at setup; every timed loop calls either a native method or a static-final exact handle.
public class BenchLeafCalls {
    @Link static native int raw_add(int a, int b);
    @Link static native int raw_abs(int a);
    @Link(name = "mem_load_long") static native long load(long[] cell);

    static final int[] X = new int[4096], Y = new int[4096];
    static final long[][] NEXT = new long[512][1];
    static final MethodHandle ADD, ABS;
    static final int ROUNDS = 1024;

    static {
        SplittableRandom rng = new SplittableRandom(717);
        for (int i = 0; i < X.length; i++) { X[i] = rng.nextInt(); Y[i] = rng.nextInt(); }
        X[0] = Integer.MIN_VALUE;
        int[] cycle = new int[NEXT.length];
        for (int i = 0; i < cycle.length; i++) {
            int j = rng.nextInt(i + 1);
            cycle[i] = cycle[j]; cycle[j] = i;
        }
        for (int i = 0; i < cycle.length; i++) NEXT[cycle[i]][0] = cycle[(i + 1) % cycle.length];

        Linker.loadLibrary("nalim_bench");
        Linker.loadLibrary("nalim_mem");
        Linker.linkClass(BenchLeafCalls.class);
        try {
            Method find;
            try { find = Linker.class.getMethod("findIntrinsic", String.class, MethodType.class); }
            catch (NoSuchMethodException baseline) { find = null; }
            ADD = handle(find, "raw_add", MethodType.methodType(int.class, int.class, int.class));
            ABS = handle(find, "raw_abs", MethodType.methodType(int.class, int.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static MethodHandle handle(Method find, String symbol, MethodType type) throws ReflectiveOperationException {
        if (find != null) {
            Optional<?> match = (Optional<?>) find.invoke(null, symbol, type);
            System.out.printf("%s.intrinsic=%d%n", symbol, match.isPresent() ? 1 : 0);
            if (match.isPresent()) return (MethodHandle) match.get();
        } else {
            System.out.printf("%s.intrinsic=0%n", symbol);
        }
        return MethodHandles.lookup().findStatic(BenchLeafCalls.class, symbol, type);
    }

    static long addDirect(int rounds) {
        long sum = 0;
        for (int r = 0; r < rounds; r++) for (int i = 0; i < X.length; i++) sum += raw_add(X[i] ^ r, Y[i]);
        return sum;
    }

    static long addHandle(int rounds) throws Throwable {
        long sum = 0;
        for (int r = 0; r < rounds; r++) for (int i = 0; i < X.length; i++) sum += (int) ADD.invokeExact(X[i] ^ r, Y[i]);
        return sum;
    }

    static long absDirect(int rounds) {
        long sum = 0;
        for (int r = 0; r < rounds; r++) for (int x : X) sum += raw_abs(x ^ r);
        return sum;
    }

    static long absHandle(int rounds) throws Throwable {
        long sum = 0;
        for (int r = 0; r < rounds; r++) for (int x : X) sum += (int) ABS.invokeExact(x ^ r);
        return sum;
    }

    static long chase(int rounds) {
        int at = 0;
        long sum = 0;
        for (int i = 0, n = rounds * X.length; i < n; i++) {
            at = (int) load(NEXT[at]);
            sum += at;
        }
        return sum;
    }

    interface Kernel { long run(int rounds) throws Throwable; }

    static void measure(String name, Kernel kernel, long expected) throws Throwable {
        for (int i = 0; i < 4; i++) kernel.run(ROUNDS / 4);
        long[] times = new long[9];
        for (int i = 0; i < times.length; i++) {
            long start = System.nanoTime();
            long sum = kernel.run(ROUNDS);
            times[i] = System.nanoTime() - start;
            if (sum != expected) throw new AssertionError(name + " checksum: " + sum + " != " + expected);
        }
        Arrays.sort(times);
        System.out.printf("%s.ns/element=%.4f%n", name, times[times.length / 2] / (double) (X.length * ROUNDS));
    }

    public static void main(String[] args) throws Throwable {
        long add = 0, abs = 0;
        for (int r = 0; r < ROUNDS; r++) for (int i = 0; i < X.length; i++) {
            add += (X[i] ^ r) + Y[i]; abs += Math.abs(X[i] ^ r);
        }
        measure("add.direct", BenchLeafCalls::addDirect, add);
        measure("add.handle", BenchLeafCalls::addHandle, add);
        measure("abs.direct", BenchLeafCalls::absDirect, abs);
        measure("abs.handle", BenchLeafCalls::absHandle, abs);
        // NEXT is a single cycle permutation; each complete lap visits 0..NEXT.length-1.
        measure("load.chase", BenchLeafCalls::chase, (long) ROUNDS * X.length * (NEXT.length - 1) / 2);
    }
}
