import com.sun.management.ThreadMXBean;
import one.nalim.Linker;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

// Compare jars in separate JVMs with the same libnalim_bench and VM options.
public class BenchSymbolLookup {
    private static final int LOOKUPS = 65_536;

    private static long lookup(String symbol) {
        long sum = 0;
        for (int i = 0; i < LOOKUPS; i++) {
            sum += Linker.findAddress(symbol);
        }
        return sum;
    }

    private static void measure(String label, String symbol, boolean hit, ThreadMXBean mx, long threadId) {
        long address = Linker.findAddress(symbol);
        if ((address != 0) != hit) throw new AssertionError("Unexpected lookup result for " + symbol);
        long expected = address * LOOKUPS;
        long[] times = new long[9];
        long totalBytes = 0;
        for (int i = -5; i < times.length; i++) {
            long before = mx.getThreadAllocatedBytes(threadId);
            long start = System.nanoTime();
            long sum = lookup(symbol);
            long elapsed = System.nanoTime() - start;
            long after = mx.getThreadAllocatedBytes(threadId);
            if (sum != expected) throw new AssertionError("Lookup checksum changed for " + symbol);
            if (before < 0 || after < before) throw new IllegalStateException("Allocation counter unavailable");
            if (i >= 0) {
                times[i] = elapsed;
                totalBytes += after - before;
            }
        }
        Arrays.sort(times);
        System.out.printf(Locale.ROOT, "%s.ns/lookup=%.3f %s.heapBytes/lookup=%.3f%n", label,
                times[times.length / 2] / (double) LOOKUPS, label, totalBytes / (double) (LOOKUPS * times.length));
    }

    public static void main(String[] args) {
        Linker.loadLibrary("nalim_bench");
        ThreadMXBean mx = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);
        if (mx == null || !mx.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("A calling-thread allocation counter is required");
        }
        if (!mx.isThreadAllocatedMemoryEnabled()) mx.setThreadAllocatedMemoryEnabled(true);
        long threadId = Thread.currentThread().getId();
        measure("symbolHit", "raw_add", true, mx, threadId);
        measure("symbolMiss", "nalim_bench_no_such_symbol", false, mx, threadId);
    }
}
