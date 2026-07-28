import one.nalim.Link;
import one.nalim.Linker;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * The user-visible question: is nalim faster than Panama for the actual
 * Rustmi math calls? Same librustmi.so, three bindings per function:
 * nalim, Panama default, Panama critical(false).
 *
 *   java -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI \
 *        --enable-native-access=ALL-UNNAMED \
 *        -cp ../nalim.jar:. -Drustmi.so=<path> BenchRustmiVsPanama
 */
public class BenchRustmiVsPanama {

    @Link static native float  sin_double(double x);
    @Link static native double atan_2(double y, double x);
    @Link static native float  lerp_float(float d, float s, float e);
    @Link static native int    lerp_int(float d, int s, int e);
    @Link static native double map_double(double v, double a, double b, double c, double e);

    static final MethodHandle P_SIN, P_ATAN2, P_LERPF, P_LERPI, P_MAPD;
    static final MethodHandle C_SIN, C_ATAN2, C_LERPF, C_LERPI, C_MAPD;

    static {
        String so = System.getProperty("rustmi.so", "librustmi.so");
        Linker.loadLibrary(new java.io.File(so).getAbsolutePath());
        Linker.linkClass(BenchRustmiVsPanama.class);

        System.load(new java.io.File(so).getAbsolutePath());
        var linker = java.lang.foreign.Linker.nativeLinker();
        SymbolLookup lookup = SymbolLookup.loaderLookup();

        var sinD  = FunctionDescriptor.of(JAVA_FLOAT, JAVA_DOUBLE);
        var atanD = FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE);
        var lerpF = FunctionDescriptor.of(JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);
        var lerpI = FunctionDescriptor.of(JAVA_INT, JAVA_FLOAT, JAVA_INT, JAVA_INT);
        var mapD  = FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE);

        P_SIN   = linker.downcallHandle(lookup.find("sin_double").orElseThrow(), sinD);
        P_ATAN2 = linker.downcallHandle(lookup.find("atan_2").orElseThrow(), atanD);
        P_LERPF = linker.downcallHandle(lookup.find("lerp_float").orElseThrow(), lerpF);
        P_LERPI = linker.downcallHandle(lookup.find("lerp_int").orElseThrow(), lerpI);
        P_MAPD  = linker.downcallHandle(lookup.find("map_double").orElseThrow(), mapD);

        var crit = java.lang.foreign.Linker.Option.critical(false);
        C_SIN   = linker.downcallHandle(lookup.find("sin_double").orElseThrow(), sinD, crit);
        C_ATAN2 = linker.downcallHandle(lookup.find("atan_2").orElseThrow(), atanD, crit);
        C_LERPF = linker.downcallHandle(lookup.find("lerp_float").orElseThrow(), lerpF, crit);
        C_LERPI = linker.downcallHandle(lookup.find("lerp_int").orElseThrow(), lerpI, crit);
        C_MAPD  = linker.downcallHandle(lookup.find("map_double").orElseThrow(), mapD, crit);
    }

    static final int N = 20_000_000;

    interface Op { double run(int i) throws Throwable; }

    static void bench(String name, Op op) throws Throwable {
        double sink = 0;
        for (int i = 0; i < 2_000_000; i++) sink += op.run(i);       // warmup
        long t0 = System.nanoTime();
        for (int i = 0; i < N; i++) sink += op.run(i);
        long dt = System.nanoTime() - t0;
        System.out.printf("  %-22s %7.2f ns/op   (sink=%.3f)%n", name, (double) dt / N, sink);
    }

    public static void main(String[] args) throws Throwable {
        System.out.println("java.version = " + System.getProperty("java.version"));

        System.out.println("--- sin_double(double) -> float ---");
        bench("nalim", i -> sin_double(i * 0.001));
        bench("panama", i -> (float) P_SIN.invokeExact((double) (i * 0.001)));
        bench("panama critical", i -> (float) C_SIN.invokeExact((double) (i * 0.001)));

        System.out.println("--- atan_2(double,double) ---");
        bench("nalim", i -> atan_2(i * 0.001, 42.0));
        bench("panama", i -> (double) P_ATAN2.invokeExact((double) (i * 0.001), 42.0));
        bench("panama critical", i -> (double) C_ATAN2.invokeExact((double) (i * 0.001), 42.0));

        System.out.println("--- lerp_float(float,float,float) ---");
        bench("nalim", i -> lerp_float(i * 1e-7f, 1.0f, 100.0f));
        bench("panama", i -> (float) P_LERPF.invokeExact(i * 1e-7f, 1.0f, 100.0f));
        bench("panama critical", i -> (float) C_LERPF.invokeExact(i * 1e-7f, 1.0f, 100.0f));

        System.out.println("--- lerp_int(float,int,int) ---");
        bench("nalim", i -> lerp_int(i * 1e-7f, 0, 1000));
        bench("panama", i -> (int) P_LERPI.invokeExact(i * 1e-7f, 0, 1000));
        bench("panama critical", i -> (int) C_LERPI.invokeExact(i * 1e-7f, 0, 1000));

        System.out.println("--- map_double(5 doubles) ---");
        bench("nalim", i -> map_double(i * 0.001, 0.0, 1000.0, -1.0, 1.0));
        bench("panama", i -> (double) P_MAPD.invokeExact(i * 0.001, 0.0, 1000.0, -1.0, 1.0));
        bench("panama critical", i -> (double) C_MAPD.invokeExact(i * 0.001, 0.0, 1000.0, -1.0, 1.0));
    }
}
