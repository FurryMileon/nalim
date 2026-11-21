package one.nalim;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.CRC32;

// Per-link adapter emission only: no JVMCI installation, native execution or Windows JVM required.
// Reuse the input signatures and output buffer so bytes/frame counts emitter allocation.
public class BenchWinEmit {
    private static final Class<?> I = int.class, L = long.class, F = float.class, D = double.class;
    private static final Class<?>[][] SIGNATURES = {
            {}, {I, L, I, L}, {F, F, L, L}, {I, F, I, L}, {D, D, D, D, D},
            {I, D, L, D, I, F, L, F, I, D}, {L, L, L, L, L, L, L},
            {L, F, I, L, I, D, L, D, F, D, F, D, L, I, F, L, D, F}
    };
    private static final int GROUPS = 1024;
    private static volatile long sink;

    private static void emit(AMD64WindowsCallingConvention cc, ByteBuffer buf) {
        for (int i = 0; i < GROUPS; i++) {
            for (Class<?>[] types : SIGNATURES) {
                if ((i & 1) == 0) {
                    cc.emitDirectFramedCall(buf, long.class, types, null);
                } else {
                    cc.emitFramedCall(buf, 0x123456789abcdef0L, long.class, types, null);
                }
            }
        }
    }

    public static void main(String[] args) {
        AMD64WindowsCallingConvention cc = new AMD64WindowsCallingConvention();
        ByteBuffer buf = ByteBuffer.allocate(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
        ThreadMXBean mx = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);
        mx.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        CRC32 crc = new CRC32();
        long[] times = new long[21], bytes = new long[times.length];
        for (int i = -30; i < times.length; i++) {
            buf.clear();
            long before = mx.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            emit(cc, buf);
            long elapsed = System.nanoTime() - start;
            long allocated = mx.getThreadAllocatedBytes(thread) - before;
            if (i >= 0) {
                times[i] = elapsed;
                bytes[i] = allocated;
            }
            // Consume the complete batch outside the measured interval.
            crc.reset();
            crc.update(buf.array(), 0, buf.position());
            sink = crc.getValue();
        }
        Arrays.sort(times);
        Arrays.sort(bytes);
        double frames = GROUPS * SIGNATURES.length;
        System.out.printf(Locale.ROOT, "winEmit.ns/frame=%.2f%nwinEmit.heapBytes/frame=%.2f%n",
                times[times.length / 2] / frames, bytes[bytes.length / 2] / frames);
    }
}
