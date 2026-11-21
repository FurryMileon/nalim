/*
 * Copyright (C) 2022 Andrei Pangin
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
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package one.nalim;

import java.lang.annotation.Annotation;
import java.nio.ByteBuffer;

class AArch64CallingConvention extends CallingConvention {

    private final boolean darwin;

    AArch64CallingConvention(boolean darwin) {
        this.darwin = darwin;
    }

    int shuffleCapacity(int parameterCount) {
        // Three instructions per register object; one extra to save x0.
        // Darwin may also need a load/store pair per spilled primitive.
        return 12 * Math.min(parameterCount, 8) + (parameterCount >= 8 ? 4 : 0)
                + (darwin ? 8 * Math.max(0, parameterCount - 8) : 0);
    }

    // AArch64 calling convention:
    //     Java: x1, x2, x3, x4, x5, x6, x7, x0, stack
    //   Native: x0, x1, x2, x3, x4, x5, x6, x7, stack

    @Override
    public void javaToNative(ByteBuffer buf, Class<?>[] types, Annotation[][] annotations) {
        if (types.length >= 8) {
            int integerArgs = 0;
            for (Class<?> type : types) {
                if (type != float.class && type != double.class && ++integerArgs == 8) {
                    // Preserve the eighth Java GP argument before overwriting x0.
                    buf.putInt(0xaa0003e8);  // mov x8, x0
                    break;
                }
            }
        }

        int gp = 0, fp = 0, javaStack = 0, nativeStack = 0;
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            if (type == float.class || type == double.class) {
                if (fp++ < 8) continue;  // Both conventions use v0..v7.
            } else if (gp < 8) {
                int dst = gp++;
                int src = dst + 1;  // x8 holds the original x0.
                if (!type.isPrimitive()) {
                    addOffset(buf, dst, src, baseOffset(type, annotations[i]));
                } else if (darwin && (type == byte.class || type == short.class
                        || type == char.class || type == boolean.class)) {
                    // Darwin requires the caller to extend narrow integer arguments to 32 bits.
                    int extend = type == byte.class ? 0x13001c00 : type == short.class ? 0x13003c00
                            : type == char.class ? 0x53003c00 : 0x53001c00;  // sxtb/sxth/uxth/uxtb
                    buf.putInt(extend | src << 5 | dst);
                } else {
                    buf.putInt((type == long.class ? 0xaa0003e0 : 0x2a0003e0) | src << 16 | dst);
                }
                continue;
            } else if (!type.isPrimitive()) {
                throw new IllegalArgumentException("Too many object arguments");
            }

            // Linux and HotSpot use eight-byte stack slots. Darwin instead
            // naturally aligns each fixed C parameter at its actual width.
            // No separate frame is needed: only primitive outgoing slots are
            // overwritten (stack references are rejected), after the entry
            // barrier and with no safepoint before returning to the caller.
            if (darwin) {
                int shift = type == byte.class || type == boolean.class ? 0
                        : type == short.class || type == char.class ? 1
                        : type == int.class || type == float.class ? 2 : 3;
                int size = 1 << shift;
                nativeStack = (nativeStack + size - 1) & -size;
                if (nativeStack != javaStack) {
                    // Pack toward sp in argument order: no later input is overwritten.
                    // x9 is HotSpot's rscratch2, independent of the saved x0 in x8.
                    // Scaled offsets fit imm12 even for a maximal Java signature.
                    buf.putInt(0x394003e9 | shift << 30 | (javaStack >> shift) << 10);   // ldr[b/h] w9/x9
                    buf.putInt(0x390003e9 | shift << 30 | (nativeStack >> shift) << 10); // str[b/h] w9/x9
                }
                javaStack += 8;
                nativeStack += size;
            }
        }
    }

    private static void addOffset(ByteBuffer buf, int dst, int src, int offset) {
        if (offset < 0) throw new IllegalArgumentException("Negative object offset: " + offset);
        if (offset >>> 24 != 0) {
            // Two ADD immediates cannot cover this offset. dst is distinct from src.
            buf.putInt(0x52a00000 | (offset >>> 16) << 5 | dst);             // movz wD, #hi, lsl #16
            if ((offset & 0xffff) != 0) {
                buf.putInt(0x72800000 | (offset & 0xffff) << 5 | dst);      // movk wD, #lo
            }
            buf.putInt(0x8b000000 | dst << 16 | src << 5 | dst);            // add xD, xS, xD
            return;
        }
        if (offset >= 4096) {
            buf.putInt(0x91400000 | (offset >>> 12) << 10 | src << 5 | dst); // add xD, xS, #hi, lsl #12
            offset &= 0xfff;
            if (offset == 0) return;
            src = dst;
        }
        buf.putInt(0x91000000 | offset << 10 | src << 5 | dst);              // add xD, xS, #lo
    }

    @Override
    public void emitCall(ByteBuffer buf, long address) {
        int a0 = (int) address & 0xffff;
        int a1 = (int) (address >>> 16) & 0xffff;
        int a2 = (int) (address >>> 32) & 0xffff;
        int a3 = (int) (address >>> 48);

        buf.putInt(0xd2800009 | a0 << 5);               // movz x9, #0xffff
        if (a1 != 0) buf.putInt(0xf2a00009 | a1 << 5);  // movk x9, #0xffff, lsl #16
        if (a2 != 0) buf.putInt(0xf2c00009 | a2 << 5);  // movk x9, #0xffff, lsl #32
        if (a3 != 0) buf.putInt(0xf2e00009 | a3 << 5);  // movk x9, #0xffff, lsl #48

        buf.putInt(0xd61f0120);                         // br x9
    }
}
