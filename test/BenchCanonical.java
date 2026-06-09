import one.nalim.Library;
import one.nalim.Link;
import one.nalim.Linker;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

@Library("nalim_bench")
public class BenchCanonical {

    @Link
    public static native int raw_add(int a, int b);

    @Link
    public static native int raw_abs(int x);

    @Link
    public static native int raw_noop();

    private static final MethodHandle add_panama;
    private static final MethodHandle abs_panama;
    private static final MethodHandle noop_panama;
    private static final MethodHandle add_panama_crit;
    private static final MethodHandle abs_panama_crit;
    private static final MethodHandle noop_panama_crit;

    static {
        // libnalim_bench.so lives in test/; Linker has @Library("nalim_bench") which
        // requests System.loadLibrary — depends on -Djava.library.path or LD_LIBRARY_PATH.
        Linker.linkClass(BenchCanonical.class);

        var linker = java.lang.foreign.Linker.nativeLinker();
        var lookup = SymbolLookup.loaderLookup();   // looks up symbols from libs loaded by this ClassLoader

        var INT  = ValueLayout.JAVA_INT;
        var crit = java.lang.foreign.Linker.Option.critical(false);

        add_panama       = linker.downcallHandle(lookup.find("raw_add" ).orElseThrow(), FunctionDescriptor.of(INT, INT, INT));
        abs_panama       = linker.downcallHandle(lookup.find("raw_abs" ).orElseThrow(), FunctionDescriptor.of(INT, INT));
        noop_panama      = linker.downcallHandle(lookup.find("raw_noop").orElseThrow(), FunctionDescriptor.of(INT));
        add_panama_crit  = linker.downcallHandle(lookup.find("raw_add" ).orElseThrow(), FunctionDescriptor.of(INT, INT, INT), crit);
        abs_panama_crit  = linker.downcallHandle(lookup.find("raw_abs" ).orElseThrow(), FunctionDescriptor.of(INT, INT),      crit);
        noop_panama_crit = linker.downcallHandle(lookup.find("raw_noop").orElseThrow(), FunctionDescriptor.of(INT),           crit);
    }

    private static final int N = 20_000_000;
    private static final int WARMUP = 500_000;

    public static void main(String[] args) throws Throwable {
        System.out.println("java.version = " + System.getProperty("java.version"));

        long sink = 0;
        for (int i = 0; i < WARMUP; i++) {
            sink += raw_add(1, i);
            sink += raw_abs(-i);
            sink += raw_noop();
            sink += (int) add_panama.invokeExact(1, i);
            sink += (int) abs_panama.invokeExact(-i);
            sink += (int) noop_panama.invokeExact();
            sink += (int) add_panama_crit.invokeExact(1, i);
            sink += (int) abs_panama_crit.invokeExact(-i);
            sink += (int) noop_panama_crit.invokeExact();
        }
        System.out.println("warmup sink = " + sink);

        bench("raw_add  Nalim",         () -> (long) raw_add(1, 2));
        bench("raw_add  Panama",        () -> { try { return (long)(int) add_panama.invokeExact(1, 2); } catch (Throwable t) { throw new RuntimeException(t); } });
        bench("raw_add  Panama crit",   () -> { try { return (long)(int) add_panama_crit.invokeExact(1, 2); } catch (Throwable t) { throw new RuntimeException(t); } });
        System.out.println();
        bench("raw_abs  Nalim",         () -> (long) raw_abs(-7));
        bench("raw_abs  Panama",        () -> { try { return (long)(int) abs_panama.invokeExact(-7); } catch (Throwable t) { throw new RuntimeException(t); } });
        bench("raw_abs  Panama crit",   () -> { try { return (long)(int) abs_panama_crit.invokeExact(-7); } catch (Throwable t) { throw new RuntimeException(t); } });
        System.out.println();
        bench("raw_noop Nalim",         () -> (long) raw_noop());
        bench("raw_noop Panama",        () -> { try { return (long)(int) noop_panama.invokeExact(); } catch (Throwable t) { throw new RuntimeException(t); } });
        bench("raw_noop Panama crit",   () -> { try { return (long)(int) noop_panama_crit.invokeExact(); } catch (Throwable t) { throw new RuntimeException(t); } });
    }

    interface LongCall { long call(); }

    static void bench(String name, LongCall fn) {
        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int run = 0; run < 5; run++) {
            long s = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) s += fn.call();
            long t1 = System.nanoTime();
            sink = s;
            double ns = (t1 - t0) / (double) N;
            if (ns < best) best = ns;
        }
        System.out.printf("  %-22s : %6.3f ns/op   (sink=%d)%n", name, best, sink);
    }
}
