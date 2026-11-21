/*
 * Copyright (C) 2026
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package one.nalim;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/** Mapping proofs for native code and literals. Callers keep the originating library loaded. */
final class NativeMappings {
    // Sorted, disjoint triples: inclusive start, exclusive end, executable flag.
    // Never merge adjacent VMAs, even when their permissions match.
    private static final long[] NO_RANGES = new long[0];
    private static volatile long[] cached;
    private static final int MAX_JUMPS = 4;
    private static final Path PROCESS_MEMORY = Paths.get("/proc/self/mem");

    private NativeMappings() {
    }

    static synchronized void invalidate() {
        cached = null;
    }

    static boolean isReadOnlyRange(long address, int size) {
        if (size <= 0) return false;
        long[] ranges = ranges();
        int i = containing(ranges, address);
        return i >= 0 && Long.compareUnsigned(ranges[i + 1] - address, size) >= 0;
    }

    static int pageWindow(long address, int max) {
        return Math.min(max, 4096 - ((int) address & 4095));
    }

    // An optional lookup may name data. A fresh RX proof alone does not make
    // a subsequent Unsafe read fault-contained; ask the kernel for the bytes.
    static byte[] readExecutablePrefix(long address, int max) {
        if (address <= 0) return null;
        long[] ranges = parse();
        int i = containing(ranges, address);
        if (i < 0 || ranges[i + 2] == 0) return null;
        int n = bounded(pageWindow(address, max), ranges[i + 1] - address);
        byte[] bytes = new byte[n];
        return readAt(address, ByteBuffer.wrap(bytes)) ? bytes : null;
    }

    private static boolean readAt(long address, ByteBuffer dst) {
        try (FileChannel memory = FileChannel.open(PROCESS_MEMORY)) {
            int n = dst.remaining();
            return memory.read(dst, address) == n;
        } catch (IOException | SecurityException unavailable) {
            return false;
        }
    }

    // Only queried after a leaf could not be decoded on its entry page.
    // A cached snapshot cannot prove the *next* page is still readable.
    static synchronized int extendCodeWindow(long address, byte[] code, int first) {
        long[] ranges = cached = parse();
        int i = containing(ranges, address);
        if (i < 0 || ranges[i + 2] == 0) return first;
        int n = bounded(code.length, ranges[i + 1] - address);
        if (n <= first || !readAt(address + first, ByteBuffer.wrap(code, first, n - first))) return first;
        return n;
    }

    private static int bounded(int max, long available) {
        return Long.compareUnsigned(available, max) < 0 ? (int) available : max;
    }

    // Copy the caller-supplied function's entry page once. Further reads of
    // speculative jump targets must go through the kernel, not through Unsafe.
    // A nonzero return owns pageWindow(result, code.length) valid bytes in code.
    static long copyEntry(long address, byte[] code) {
        int max = code.length;
        int n = pageWindow(address, max);
        NativeMemory.copyToArray(address, code, 0, n);
        int length = directJumpLength(code, n);
        if (length == 0) return address;
        long delta = jumpDelta(code, length);
        if (delta >= 0 && delta < n) return address; // validate with the normal CFG

        long[] ranges = parse(); // A cached VMA may have split since the last link.
        int i = containing(ranges, address);
        if (i < 0 || ranges[i + 2] == 0) return 0;
        long start = ranges[i], end = ranges[i + 1], current = address;
        long[] prior = null;
        try (FileChannel memory = FileChannel.open(PROCESS_MEMORY)) {
            ByteBuffer dst = ByteBuffer.wrap(code);
            for (int hops = 0; ; hops++) {
                long target = current + delta;
                if (Long.compareUnsigned(target, start) < 0 || Long.compareUnsigned(target, end) >= 0
                        || target == address || (delta >= 0 && delta < length)) return 0;
                for (int p = 0; p < 2 * hops; p += 2) {
                    if (Long.compareUnsigned(target - prior[p], prior[p + 1]) < 0
                            || Long.compareUnsigned(prior[p] - current, length) < 0) return 0;
                }
                if (delta >= 0 && delta < bounded(max, end - current)) return current;
                if (hops == MAX_JUMPS) return 0;

                n = pageWindow(target, max);
                if (Long.compareUnsigned(end - target, n) < 0) return 0;
                dst.clear().limit(n);
                if (memory.read(dst, target) != n) return 0;
                int next = directJumpLength(code, n);
                if (next == 0) return target;
                if (prior == null) prior = new long[2 * MAX_JUMPS];
                prior[2 * hops] = current;
                prior[2 * hops + 1] = length;
                current = target;
                length = next;
                delta = jumpDelta(code, length);
            }
        } catch (IOException | SecurityException unavailable) {
            return 0;
        }
    }

    private static int directJumpLength(byte[] code, int n) {
        int p = 0;
        if (code[0] == (byte) 0xf3) {
            if (n < 5 || code[1] != 0x0f || code[2] != 0x1e || code[3] != (byte) 0xfa) return 0;
            p = 4;
        }
        int length = code[p] == (byte) 0xeb ? 2 : code[p] == (byte) 0xe9 ? 5 : 0;
        return length == 0 || n - p < length ? 0 : p + length;
    }

    private static long jumpDelta(byte[] code, int length) {
        if (length == 2 || length == 6) return length + code[length - 1]; // rel8, with or without ENDBR64
        int p = length - 4;
        int disp = (code[p] & 255) | (code[p + 1] & 255) << 8 | (code[p + 2] & 255) << 16 | code[p + 3] << 24;
        return length + (long) disp;
    }

    private static int containing(long[] ranges, long address) {
        int lo = 0, hi = ranges.length / 3;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            int i = mid * 3;
            if (Long.compareUnsigned(address, ranges[i]) < 0) hi = mid;
            else if (Long.compareUnsigned(address, ranges[i + 1]) >= 0) lo = mid + 1;
            else return i;
        }
        return -1;
    }

    private static long[] ranges() {
        long[] result = cached;
        if (result == null) {
            synchronized (NativeMappings.class) {
                result = cached;
                if (result == null) cached = result = parse();
            }
        }
        return result;
    }

    private static long[] parse() {
        // Only the ASCII address/perms prefix matters. Paths need not be valid UTF-8.
        try (BufferedReader reader = Files.newBufferedReader(Paths.get("/proc/self/maps"), StandardCharsets.ISO_8859_1)) {
            long[] result = new long[3 * 128];
            int used = 0;
            for (String line; (line = reader.readLine()) != null; ) {
                int sp = line.indexOf(' ');
                if (sp < 0 || sp + 3 >= line.length() || line.charAt(sp + 1) != 'r' || line.charAt(sp + 2) != '-') continue;
                int dash = line.indexOf('-');
                if (dash <= 0 || dash >= sp) return NO_RANGES;
                long start = Long.parseUnsignedLong(line, 0, dash, 16);
                long end = Long.parseUnsignedLong(line, dash + 1, sp, 16);
                if (Long.compareUnsigned(start, end) >= 0 || (used != 0 && Long.compareUnsigned(start, result[used - 2]) < 0)) {
                    return NO_RANGES;
                }
                if (used == result.length) result = Arrays.copyOf(result, used * 2);
                result[used++] = start;
                result[used++] = end;
                result[used++] = line.charAt(sp + 3) == 'x' ? 1 : 0;
            }
            return Arrays.copyOf(result, used);
        } catch (IOException | SecurityException | NumberFormatException e) {
            return NO_RANGES;
        }
    }
}
