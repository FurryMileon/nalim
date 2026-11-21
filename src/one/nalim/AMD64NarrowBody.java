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

import java.nio.ByteBuffer;

/** Layout of a verified AMD64 leaf whose narrow exits cannot use the terminal-RET fast path. */
final class AMD64NarrowBody {
    // All four forms of movsx/movzx emitted by emitAMD64ReturnNormalization are three bytes.
    static final int NORMALIZATION_SIZE = 3;

    private AMD64NarrowBody() {
    }

    /** Emit into the pre-sized little-endian buffer, or decline an overflowing rel8 before writing. */
    static int[] emit(ByteBuffer out, byte[] original, byte[] lengths, int end, Class<?> returnType) {
        int[] map = new int[end + 1];
        int extra = 0;
        for (int i = 0; i < end; i++) {
            // A RET address denotes entry to its normalizer. This is also exactly the
            // next-IP value needed by a RIP-relative instruction immediately before it.
            map[i] = i + extra;
            if ((lengths[i] == 1 && original[i] == (byte) 0xC3)
                    || (lengths[i] == 2 && original[i] == (byte) 0xF3 && original[i + 1] == (byte) 0xC3)) {
                extra += NORMALIZATION_SIZE;
            }
        }
        map[end] = end + extra;

        // No widening: check all potentially overflowing edges before committing any bytes.
        if (map[end] > 128) {
            for (int i = 0; i < end; i++) {
                if (lengths[i] == 2 && isShortBranch(original[i] & 0xFF)) {
                    int next = i + 2;
                    int disp = map[next + original[i + 1]] - map[next];
                    if (disp != (byte) disp) return null;
                }
            }
        }

        int copyFrom = 0;
        for (int i = 0; i < end; i++) {
            int length = lengths[i];
            if (length <= 0) continue;
            if (map[i + 1] != map[i] + 1) {
                out.put(original, copyFrom, i - copyFrom);
                CallingConvention.emitAMD64ReturnNormalization(out, returnType);
                copyFrom = i; // keep the original RET or REP RET
                continue;
            }
            int op = original[i] & 0xFF;
            int next = i + length;
            int width;
            int oldDisp;
            if (length == 2 && isShortBranch(op)) {
                width = 1;
                oldDisp = original[next - 1];
            } else if ((length == 5 && op == 0xE9)
                    || (length == 6 && op == 0x0F && (original[i + 1] & 0xF0) == 0x80)) {
                width = 4;
                oldDisp = (original[next - 4] & 0xFF) | (original[next - 3] & 0xFF) << 8
                        | (original[next - 2] & 0xFF) << 16 | original[next - 1] << 24;
            } else {
                continue;
            }
            int disp = map[next + oldDisp] - map[next];
            if (disp == oldDisp) continue;
            out.put(original, copyFrom, next - width - copyFrom);
            if (width == 1) {
                out.put((byte) disp);
            } else {
                out.putInt(disp);
            }
            copyFrom = next;
        }
        out.put(original, copyFrom, end - copyFrom);
        return map;
    }

    private static boolean isShortBranch(int op) {
        return op == 0xEB || (op & 0xF0) == 0x70;
    }
}
