import one.nalim.Link;
import one.nalim.Linker;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;

// The "near-zero-overhead memory path" investigation. Compares every way of
// reading/writing Java-heap and native memory from Java, at sizes from a
// single element to 512 longs:
//
//   A. C2 intrinsics    — Unsafe.getLong / MemorySegment (no call at all)
//   B. Nalim            — one FFI call, zero-copy heap array / raw pointer
//   C. Panama critical  — one FFI call via downcall MethodHandle
//
// The point: where does one Nalim call into a native kernel beat per-element
// JIT-inlined access, and what does the call cost at the crossover?
// No lambdas; one static method per row.
public class BenchMemAccess {

    @Link(name = "mem_sum_longs")  static native long nalimSumHeap(long[] p, int n);
    @Link(name = "mem_sum_longs")  static native long nalimSumNative(long addr, int n);
    @Link(name = "mem_load_long")  static native long nalimLoad(long addr);
    @Link(name = "mem_store_long") static native void nalimStore(long addr, long v);
    @Link(name = "mem_axpy_into")  static native long nalimAxpy(long accAddr, long[] src, int n);

    static final sun.misc.Unsafe U = unsafe();
    static long nativeBuf;                       // 4096 B
    static final long[] heap = new long[512];
    static MemorySegment nativeSeg;
    static MethodHandle panamaSumCrit;           // critical(true): heap or native
    static MethodHandle panamaLoadCrit;

    public static void main(String[] args) throws Throwable {
        System.out.println("java.version = " + System.getProperty("java.version"));
        Linker.loadLibrary("nalim_mem");
        Linker.linkClass(BenchMemAccess.class);
        System.out.println("inlined = " + Linker.getInlinedMethodCount());

        nativeBuf = U.allocateMemory(4096);
        nativeSeg = MemorySegment.ofAddress(nativeBuf).reinterpret(4096);
        for (int i = 0; i < 512; i++) {
            heap[i] = 0x9E3779B97F4A7C15L * i;
            U.putLong(nativeBuf + i * 8L, heap[i]);
        }

        var linker = java.lang.foreign.Linker.nativeLinker();
        var lookup = SymbolLookup.loaderLookup();
        panamaSumCrit = linker.downcallHandle(
                lookup.find("mem_sum_longs").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                java.lang.foreign.Linker.Option.critical(true));
        panamaLoadCrit = linker.downcallHandle(
                lookup.find("mem_load_long").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
                java.lang.foreign.Linker.Option.critical(true));

        // sanity
        long expect = 0;
        for (int i = 0; i < 16; i++) expect += heap[i];
        if (nalimSumHeap(heap, 16) != expect) throw new AssertionError("nalim heap");
        if (nalimSumNative(nativeBuf, 16) != expect) throw new AssertionError("nalim native");
        if ((long) panamaSumCrit.invokeExact(MemorySegment.ofArray(heap), 16) != expect)
            throw new AssertionError("panama heap");

        // warmup all paths
        long sink = 0;
        for (int i = 0; i < 2_000_000; i++) {
            sink += javaSumHeap(16);
            sink += unsafeSumNative(16);
            sink += segmentSumNative(16);
            sink += nalimSumHeap(heap, 16);
            sink += nalimSumNative(nativeBuf, 16);
            sink += (long) panamaSumCrit.invokeExact(MemorySegment.ofArray(heap), 16);
            sink += (long) panamaSumCrit.invokeExact(nativeSeg, 16);
            sink += U.getLong(nativeBuf + ((i & 15) * 8L));
            sink += nalimLoad(nativeBuf + ((i & 15) * 8L));
            sink += (long) panamaLoadCrit.invokeExact(nativeSeg);
            sink += nativeSeg.get(ValueLayout.JAVA_LONG, (i & 15) * 8L);
        }
        System.out.println("warmup sink = " + sink);

        System.out.println("--- single element ---");
        benchUnsafePeek();
        benchSegmentPeek();
        benchNalimPeek();
        benchPanamaPeek();
        benchUnsafePoke();
        benchNalimPoke();

        System.out.println("--- bulk sum over n longs: one call vs JIT loop ---");
        int[] ns = {4, 16, 64, 512};
        for (int n : ns) {
            System.out.println("  n = " + n + ":");
            benchJavaHeapLoop(n);
            benchUnsafeNativeLoop(n);
            benchSegmentNativeLoop(n);
            benchNalimHeap(n);
            benchNalimNative(n);
            benchPanamaHeap(n);
            benchPanamaNative(n);
        }
    }

    // ── single element ──────────────────────────────────────────────────

    static void benchUnsafePeek() {
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            long s = 0, t0 = System.nanoTime();
            for (int i = 0; i < 100_000_000; i++) s += U.getLong(nativeBuf + ((i & 511) * 8L));
            best = Math.min(best, (System.nanoTime() - t0) / 100_000_000.0); sink = s;
        }
        report("peek Unsafe.getLong   [intrinsic]", best, sink);
    }

    static void benchSegmentPeek() {
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            long s = 0, t0 = System.nanoTime();
            for (int i = 0; i < 100_000_000; i++) s += nativeSeg.get(ValueLayout.JAVA_LONG, (i & 511) * 8L);
            best = Math.min(best, (System.nanoTime() - t0) / 100_000_000.0); sink = s;
        }
        report("peek MemorySegment    [intrinsic+bounds]", best, sink);
    }

    static void benchNalimPeek() {
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            long s = 0, t0 = System.nanoTime();
            for (int i = 0; i < 100_000_000; i++) s += nalimLoad(nativeBuf + ((i & 511) * 8L));
            best = Math.min(best, (System.nanoTime() - t0) / 100_000_000.0); sink = s;
        }
        report("peek Nalim call       [inline nmethod]", best, sink);
    }

    static void benchPanamaPeek() {
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            long s = 0, t0 = System.nanoTime();
            try {
                for (int i = 0; i < 50_000_000; i++) s += (long) panamaLoadCrit.invokeExact(nativeSeg);
            } catch (Throwable t) { throw new RuntimeException(t); }
            best = Math.min(best, (System.nanoTime() - t0) / 50_000_000.0); sink = s;
        }
        report("peek Panama critical  [downcall]", best, sink);
    }

    static void benchUnsafePoke() {
        double best = Double.MAX_VALUE;
        for (int r = 0; r < 5; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < 100_000_000; i++) U.putLong(nativeBuf + ((i & 511) * 8L), i);
            best = Math.min(best, (System.nanoTime() - t0) / 100_000_000.0);
        }
        report("poke Unsafe.putLong   [intrinsic]", best, 0);
    }

    static void benchNalimPoke() {
        double best = Double.MAX_VALUE;
        for (int r = 0; r < 5; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < 100_000_000; i++) nalimStore(nativeBuf + ((i & 511) * 8L), i);
            best = Math.min(best, (System.nanoTime() - t0) / 100_000_000.0);
        }
        report("poke Nalim call       [inline nmethod]", best, 0);
    }

    // ── bulk ────────────────────────────────────────────────────────────

    static long javaSumHeap(int n) {
        long s = 0;
        for (int i = 0; i < n; i++) s += heap[i];
        return s;
    }

    static long unsafeSumNative(int n) {
        long s = 0;
        for (int i = 0; i < n; i++) s += U.getLong(nativeBuf + i * 8L);
        return s;
    }

    static long segmentSumNative(int n) {
        long s = 0;
        for (int i = 0; i < n; i++) s += nativeSeg.getAtIndex(ValueLayout.JAVA_LONG, i);
        return s;
    }

    static int reps(int n) { return Math.max(2_000_000, 400_000_000 / (n * 4)); }

    static void benchJavaHeapLoop(int n) {
        int N = reps(n);
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            long s = 0, t0 = System.nanoTime();
            for (int i = 0; i < N; i++) s += javaSumHeap(n);
            best = Math.min(best, (System.nanoTime() - t0) / (double) N); sink = s;
        }
        report("    Java loop heap[]        ", best, sink);
    }

    static void benchUnsafeNativeLoop(int n) {
        int N = reps(n);
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            long s = 0, t0 = System.nanoTime();
            for (int i = 0; i < N; i++) s += unsafeSumNative(n);
            best = Math.min(best, (System.nanoTime() - t0) / (double) N); sink = s;
        }
        report("    Unsafe loop native      ", best, sink);
    }

    static void benchSegmentNativeLoop(int n) {
        int N = reps(n);
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            long s = 0, t0 = System.nanoTime();
            for (int i = 0; i < N; i++) s += segmentSumNative(n);
            best = Math.min(best, (System.nanoTime() - t0) / (double) N); sink = s;
        }
        report("    Segment loop native     ", best, sink);
    }

    static void benchNalimHeap(int n) {
        int N = reps(n);
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            long s = 0, t0 = System.nanoTime();
            for (int i = 0; i < N; i++) s += nalimSumHeap(heap, n);
            best = Math.min(best, (System.nanoTime() - t0) / (double) N); sink = s;
        }
        report("    Nalim 1 call heap[]     ", best, sink);
    }

    static void benchNalimNative(int n) {
        int N = reps(n);
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            long s = 0, t0 = System.nanoTime();
            for (int i = 0; i < N; i++) s += nalimSumNative(nativeBuf, n);
            best = Math.min(best, (System.nanoTime() - t0) / (double) N); sink = s;
        }
        report("    Nalim 1 call native     ", best, sink);
    }

    static void benchPanamaHeap(int n) {
        int N = reps(n);
        double best = Double.MAX_VALUE; long sink = 0;
        MemorySegment heapSeg = MemorySegment.ofArray(heap);
        for (int r = 0; r < 5; r++) {
            long s = 0, t0 = System.nanoTime();
            try {
                for (int i = 0; i < N; i++) s += (long) panamaSumCrit.invokeExact(heapSeg, n);
            } catch (Throwable t) { throw new RuntimeException(t); }
            best = Math.min(best, (System.nanoTime() - t0) / (double) N); sink = s;
        }
        report("    Panama crit heap seg    ", best, sink);
    }

    static void benchPanamaNative(int n) {
        int N = reps(n);
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            long s = 0, t0 = System.nanoTime();
            try {
                for (int i = 0; i < N; i++) s += (long) panamaSumCrit.invokeExact(nativeSeg, n);
            } catch (Throwable t) { throw new RuntimeException(t); }
            best = Math.min(best, (System.nanoTime() - t0) / (double) N); sink = s;
        }
        report("    Panama crit native seg  ", best, sink);
    }

    static void report(String name, double ns, long sink) {
        System.out.printf("  %-34s : %7.3f ns/op (sink=%d)%n", name, ns, sink);
    }

    static sun.misc.Unsafe unsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
