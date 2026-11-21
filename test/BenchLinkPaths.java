import com.sun.management.ThreadMXBean;
import one.nalim.Link;
import one.nalim.Linker;

import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.function.IntToLongFunction;

// JDK 21+, x86-64 Linux. Requires the bundled nalim_bench and nalim_fx_asm libraries.
// Keep the loop and native call site identical when comparing two Nalim jars.
public class BenchLinkPaths {
    static class Plain {
        @Link static native int raw_add(int a, int b);
        @Link static native int raw_abs(int x);
        @Link static native int raw_noop();
    }

    @Link static native int fxa_forward(int x);
    @Link static native byte fxa_classify8(int x);
    private static final int CALLS = 16_000_000;

    static long plain(int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) sum += Plain.raw_add(i, 17);
        return sum;
    }

    static long forward(int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) sum += fxa_forward(i);
        return sum;
    }

    static long narrow(int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) sum += fxa_classify8((i & 3) - 1);
        return sum;
    }

    static void calls(String name, IntToLongFunction loop, long expected) {
        int n = CALLS;
        for (int i = 0; i < 4; i++) loop.applyAsLong(n / 8);
        long[] times = new long[9];
        for (int i = 0; i < times.length; i++) {
            long start = System.nanoTime();
            long sum = loop.applyAsLong(n);
            times[i] = System.nanoTime() - start;
            if (sum != expected) throw new AssertionError(name + " checksum: " + sum);
        }
        Arrays.sort(times);
        System.out.printf("%s.ns/call=%.3f%n", name, times[times.length / 2] / (double) n);
    }

    static void binding() throws Exception {
        // Fresh methods without timing class definition, GC preparation or reinstallation
        // over live nmethods. Measures reflection, code generation and real JVMCI installation.
        byte[] template;
        try (InputStream in = Plain.class.getResourceAsStream("BenchLinkPaths$Plain.class")) {
            template = in.readAllBytes();
        }
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        ThreadMXBean mx = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);
        mx.setThreadAllocatedMemoryEnabled(true);
        long[] times = new long[301];
        long bytes = 0;
        for (int i = -300; i < times.length; i++) {
            Class<?> holder = lookup.defineHiddenClass(template, false).lookupClass();
            long before = mx.getCurrentThreadAllocatedBytes();
            long t = System.nanoTime();
            Linker.linkClass(holder);
            long elapsed = System.nanoTime() - t;
            long allocated = mx.getCurrentThreadAllocatedBytes() - before;
            if (i >= 0) {
                times[i] = elapsed;
                bytes += allocated;
            }
        }
        Arrays.sort(times);
        System.out.printf("link.ns/method=%.1f link.heapBytes/method=%.1f%n",
                times[times.length / 2] / 3.0, bytes / (3.0 * times.length));
    }

    public static void main(String[] args) throws Exception {
        Linker.loadLibrary("nalim_bench");
        Linker.loadLibrary("nalim_fx_asm");
        Class<?> first = Plain.class;
        long start = System.nanoTime();
        Linker.linkClass(first);
        System.out.printf("firstClass.ms=%.3f%n", (System.nanoTime() - start) / 1e6);
        Linker.linkClass(BenchLinkPaths.class);
        System.out.printf("hot.inline=%d hot.direct=%d%n", Linker.getInlinedMethodCount(), Linker.getDirectJumpMethodCount());
        long n = CALLS;
        long sum = n * (n - 1) / 2 + n * 17;
        calls("plain", BenchLinkPaths::plain, sum);
        calls("forward", BenchLinkPaths::forward, sum);
        calls("narrow", BenchLinkPaths::narrow, (n / 4) * (-128 + 0 + 1 + 1));
        binding();
    }
}
