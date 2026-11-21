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

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/** Mapping proofs for native code and literals. Callers keep the originating library loaded. */
final class NativeMappings {
    // Sorted, disjoint pairs: inclusive start with an executable bit, exclusive end.
    // Never merge adjacent VMAs, even when their permissions match.
    private static final long EXECUTABLE = 1;
    private static final long[] NO_RANGES = new long[0];
    private final long[] ranges;
    private static final int MAX_JUMPS = 4;
    private static final Path PROCESS_MAPPINGS = Paths.get("/proc/self/maps");
    private static final Path PROCESS_MEMORY = Paths.get("/proc/self/mem");

    private NativeMappings(long[] ranges) {
        this.ranges = ranges;
    }

    // Borrowed only for the body currently being decoded. A later binding
    // must see intervening mprotect/munmap changes, even without a library load.
    static NativeMappings snapshot() {
        return new NativeMappings(parse());
    }

    boolean isReadOnlyRange(long address, int size) {
        if (size <= 0) return false;
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
        if (i < 0 || (ranges[i] & EXECUTABLE) == 0) return null;
        int n = bounded(pageWindow(address, max), ranges[i + 1] - address);
        byte[] bytes = new byte[n];
        return readAt(address, ByteBuffer.wrap(bytes)) ? bytes : null;
    }

    static FileChannel openMemory() throws IOException {
        return FileChannel.open(PROCESS_MEMORY);
    }

    private static boolean readAt(FileChannel memory, long address, ByteBuffer dst) throws IOException {
        // procfs uses a signed file offset; don't pass a high unsigned VA to FileChannel.
        int size = dst.remaining();
        return address >= 0 && memory.read(dst, address) == size;
    }

    static boolean readLiteral(FileChannel memory, long address, ByteBuffer pool, int size) throws IOException {
        int limit = pool.limit();
        pool.limit(pool.position() + size);
        try {
            return readAt(memory, address, pool);
        } finally {
            pool.limit(limit);
        }
    }

    private static boolean readAt(long address, ByteBuffer dst) {
        try (FileChannel memory = openMemory()) {
            return readAt(memory, address, dst);
        } catch (IOException | SecurityException unavailable) {
            return false;
        }
    }

    // Only queried after a leaf could not be decoded on its entry page.
    // A cached snapshot cannot prove the *next* page is still readable.
    static int extendCodeWindow(long address, byte[] code, int first) {
        long[] ranges = parse();
        int i = containing(ranges, address);
        if (i < 0 || (ranges[i] & EXECUTABLE) == 0) return first;
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
        if (i < 0 || (ranges[i] & EXECUTABLE) == 0) return 0;
        long start = ranges[i] & ~EXECUTABLE, end = ranges[i + 1], current = address;
        long[] prior = null;
        try (FileChannel memory = openMemory()) {
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
                if (!readAt(memory, target, dst)) return 0;
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
        int lo = 0, hi = ranges.length >>> 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            int i = mid << 1;
            if (Long.compareUnsigned(address, ranges[i] & ~EXECUTABLE) < 0) hi = mid;
            else if (Long.compareUnsigned(address, ranges[i + 1]) >= 0) lo = mid + 1;
            else return i;
        }
        return -1;
    }

    private static long[] parse() {
        try (InputStream maps = Files.newInputStream(PROCESS_MAPPINGS)) {
            return readRanges(maps);
        } catch (IOException | SecurityException | NumberFormatException e) {
            return NO_RANGES;
        }
    }

    // Two unsigned 64-bit addresses, dash, space and four permission bytes.
    private static final int MAP_PREFIX_SIZE = 16 + 1 + 16 + 1 + 4;

    static long[] readRanges(InputStream maps) throws IOException {
        Ranges ranges = new Ranges();
        byte[] block = new byte[4096], prefix = null;
        int pending = 0;
        for (int size; (size = maps.read(block)) != -1; ) {
            for (int start = 0; start < size; ) {
                int end = Ranges.newline(block, start, size);
                if (pending == 0 && end != size) {
                    if (!ranges.append(block, start, end)) return NO_RANGES;
                } else {
                    // Copy only the prefix of a split line, never the mapping's
                    // pathname. Unsplit lines are parsed directly in the block.
                    if (prefix == null) prefix = new byte[MAP_PREFIX_SIZE];
                    int keep = Math.min(end - start, MAP_PREFIX_SIZE - pending);
                    if (keep != 0) {
                        System.arraycopy(block, start, prefix, pending, keep);
                        pending += keep;
                    }
                    if (end != size) {
                        if (!ranges.append(prefix, 0, pending)) return NO_RANGES;
                        pending = 0;
                    }
                }
                start = end + 1;
            }
        }
        if (pending != 0 && !ranges.append(prefix, 0, pending)) return NO_RANGES;
        return Arrays.copyOf(ranges.values, ranges.used);
    }

    private static int findByte(byte[] bytes, int from, int end, int value) {
        while (from < end && bytes[from] != value) from++;
        return from;
    }

    private static long address(byte[] line, int from, int end) {
        if (from == end || end - from > 16) throw new NumberFormatException();
        long address = 0;
        while (from < end) {
            int c = line[from++], digit = c - '0';
            if (digit < 0 || digit > 9) {
                int alpha = (c | 0x20) - 'a';
                if (alpha < 0 || alpha > 5) throw new NumberFormatException();
                digit = alpha + 10;
            }
            address = (address << 4) | digit;
        }
        return address;
    }

    private static final class Ranges {
        private static final VarHandle LITTLE_ENDIAN_WORDS =
                MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
        private static final long LOW_BITS = 0x7f7f7f7f7f7f7f7fL;
        private static final long NEWLINES = '\n' * 0x0101010101010101L;

        long[] values = new long[2 * 128];
        int used;

        private static int newline(byte[] bytes, int from, int end) {
            for (; end - from >= Long.BYTES; from += Long.BYTES) {
                long word = (long) LITTLE_ENDIAN_WORDS.get(bytes, from) ^ NEWLINES;
                // Adding 0x7f to each low seven-bit lane cannot carry between lanes.
                // Only originally zero lanes still have a clear high bit.
                long matches = ~(((word & LOW_BITS) + LOW_BITS) | word) & ~LOW_BITS;
                if (matches != 0) return from + (Long.numberOfTrailingZeros(matches) >>> 3);
            }
            return findByte(bytes, from, end, '\n');
        }

        boolean append(byte[] line, int from, int end) {
            int sp = findByte(line, from, end, ' ');
            // A split line can fill the carry buffer without finishing its header.
            // Reject that snapshot rather than treating the line as a non-RO mapping.
            if (sp + 4 >= end) return false;
            if (line[sp + 1] != 'r' || line[sp + 2] != '-') return true;
            int execute = line[sp + 3], sharing = line[sp + 4];
            if ((execute != '-' && execute != 'x') || (sharing != 'p' && sharing != 's')) return false;
            int dash = findByte(line, from, sp, '-');
            if (dash <= from || dash == sp) return false;
            long start = address(line, from, dash);
            long finish = address(line, dash + 1, sp);
            // Real procfs VMAs are page-aligned; reject an odd start rather
            // than letting a malformed header leak into the execute flag.
            if ((start & EXECUTABLE) != 0 || Long.compareUnsigned(start, finish) >= 0
                    || (used != 0 && Long.compareUnsigned(start, values[used - 1]) < 0)) return false;
            if (used == values.length) values = Arrays.copyOf(values, used * 2);
            values[used++] = start | (execute == 'x' ? EXECUTABLE : 0);
            values[used++] = finish;
            return true;
        }
    }
}
