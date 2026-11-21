import com.sun.management.ThreadMXBean;
import one.nalim.Code;
import one.nalim.Linker;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

// AMD64 HotSpot; compile once and use the same class files against each jar.
public class BenchCodeBinding {
    public static class Bodies {
        @Code("b829000000c3") static native int compact();
        @Code("b8 2a 00 00 00 c3") static native int spaced();
        @Code("b82a000000 83c009 6bc003 83f055 83e804 c3") static native int arithmetic();

        public static void verify() {
            if (compact() != 41 || spaced() != 42 || arithmetic() != 200) {
                throw new AssertionError("bad installed body");
            }
        }
    }

    private static class FreshLoader extends ClassLoader {
        FreshLoader() {
            super(BenchCodeBinding.class.getClassLoader());
        }

        Class<?> define(byte[] template) {
            return defineClass(null, template, 0, template.length);
        }
    }

    public static void main(String[] args) throws Exception {
        String arch = System.getProperty("os.arch");
        if (!"amd64".equals(arch) && !"x86_64".equals(arch)) {
            throw new UnsupportedOperationException("AMD64 fixture");
        }
        byte[] template;
        try (InputStream in = BenchCodeBinding.class.getResourceAsStream("BenchCodeBinding$Bodies.class")) {
            template = in.readAllBytes();
        }
        ThreadMXBean mx = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);
        mx.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        long bytes = 0;
        long[] times = new long[301];
        for (int i = -300; i < times.length; i++) {
            // Each native method really gets its first JVMCI installation; class loading
            // and invoking the resulting code are both outside the measured interval.
            Class<?> holder = new FreshLoader().define(template);
            long before = mx.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            Linker.linkClass(holder);
            long elapsed = System.nanoTime() - start;
            long allocated = mx.getThreadAllocatedBytes(thread) - before;
            holder.getMethod("verify").invoke(null);
            if (i >= 0) {
                times[i] = elapsed;
                bytes += allocated;
            }
        }
        Arrays.sort(times);
        System.out.printf(Locale.ROOT, "code.ns/method=%.1f code.heapBytes/method=%.1f%n",
                times[times.length / 2] / 3.0, bytes / (3.0 * times.length));
    }
}
