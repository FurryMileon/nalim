import one.nalim.Code;
import one.nalim.Link;
import one.nalim.Linker;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

public class BenchVsPanama {

    // Nalim: rdtsc — pure-CPU, no syscall, ~25 cycles intrinsic.
    @Code("0f31 48c1e220 4809d0 c3")
    public static native long rdtscNalim();

    // Nalim: real libc getuid() — syscall dominates.
    @Link(name = "getuid")
    public static native int getuidNalim();

    // Nalim: real libc getpid() — syscall (cached in glibc since 2.25, very fast).
    @Link(name = "getpid")
    public static native int getpidNalim();

    // Nalim: libc abs() — pure CPU, single conditional negate. Isolates wrapper cost.
    @Link(name = "abs")
    public static native int absNalim(int x);

    private static final MethodHandle getuidPanama;
    private static final MethodHandle getpidPanama;
    private static final MethodHandle absPanama;
    private static final MethodHandle getuidPanamaCritical;
    private static final MethodHandle getpidPanamaCritical;
    private static final MethodHandle absPanamaCritical;

    static {
        Linker.linkClass(BenchVsPanama.class);

        var linker = java.lang.foreign.Linker.nativeLinker();
        var lookup = linker.defaultLookup();

        getuidPanama = linker.downcallHandle(
                lookup.find("getuid").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));

        getpidPanama = linker.downcallHandle(
                lookup.find("getpid").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));

        absPanama = linker.downcallHandle(
                lookup.find("abs").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        // critical(false) ≈ JDK 21 isTrivial — fastest supported Panama option for
        // leaf calls that don't access heap memory and don't call back into Java.
        getuidPanamaCritical = linker.downcallHandle(
                lookup.find("getuid").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT),
                java.lang.foreign.Linker.Option.critical(false));

        getpidPanamaCritical = linker.downcallHandle(
                lookup.find("getpid").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT),
                java.lang.foreign.Linker.Option.critical(false));

        absPanamaCritical = linker.downcallHandle(
                lookup.find("abs").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                java.lang.foreign.Linker.Option.critical(false));
    }

    private static final int N = 10_000_000;
    private static final int WARMUP = 200_000;

    public static void main(String[] args) throws Throwable {
        System.out.println("java.version = " + System.getProperty("java.version"));

        // Warmup
        long warmSink = 0;
        for (int i = 0; i < WARMUP; i++) {
            warmSink += rdtscNalim();
            warmSink += getuidNalim();
            warmSink += getpidNalim();
            warmSink += absNalim(-i);
            warmSink += (int) getuidPanama.invokeExact();
            warmSink += (int) getpidPanama.invokeExact();
            warmSink += (int) absPanama.invokeExact(-i);
            warmSink += (int) getuidPanamaCritical.invokeExact();
            warmSink += (int) getpidPanamaCritical.invokeExact();
            warmSink += (int) absPanamaCritical.invokeExact(-i);
        }
        System.out.println("warmup sink = " + warmSink);

        // Bench
        System.out.println("--- PURE CPU (no syscall) ---");
        bench("rdtsc           Nalim",  () -> rdtscNalim());
        bench("abs(-123)       Nalim",  () -> (long) absNalim(-123));
        bench("abs(-123)       Panama", () -> { try { return (long)(int) absPanama.invokeExact(-123); } catch (Throwable t) { throw new RuntimeException(t); } });
        bench("abs(-123) crit  Panama", () -> { try { return (long)(int) absPanamaCritical.invokeExact(-123); } catch (Throwable t) { throw new RuntimeException(t); } });
        System.out.println("--- SYSCALL ---");
        bench("getuid          Nalim",  () -> (long) getuidNalim());
        bench("getuid          Panama", () -> { try { return (long)(int) getuidPanama.invokeExact(); } catch (Throwable t) { throw new RuntimeException(t); } });
        bench("getuid    crit  Panama", () -> { try { return (long)(int) getuidPanamaCritical.invokeExact(); } catch (Throwable t) { throw new RuntimeException(t); } });
        bench("getpid          Nalim",  () -> (long) getpidNalim());
        bench("getpid          Panama", () -> { try { return (long)(int) getpidPanama.invokeExact(); } catch (Throwable t) { throw new RuntimeException(t); } });
        bench("getpid    crit  Panama", () -> { try { return (long)(int) getpidPanamaCritical.invokeExact(); } catch (Throwable t) { throw new RuntimeException(t); } });
    }

    interface LongCall { long call(); }

    static void bench(String name, LongCall fn) {
        // Three runs, take best (least-noisy estimate)
        double best = Double.MAX_VALUE;
        long total = 0;
        for (int run = 0; run < 3; run++) {
            long sink = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) sink += fn.call();
            long t1 = System.nanoTime();
            total = sink;
            double nsPerOp = (t1 - t0) / (double) N;
            if (nsPerOp < best) best = nsPerOp;
        }
        System.out.printf("  %-25s : %7.2f ns/op   (sink=%d)%n", name, best, total);
    }
}
