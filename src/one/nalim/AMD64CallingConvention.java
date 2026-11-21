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

import java.nio.ByteBuffer;

// Narrow-return bridge for HotSpot Java-ABI targets on all x86-64 hosts,
// and SysV native targets after their GP argument registers are shuffled.
// Ordinary Win64 native calls use a separate positional, shadow-space frame.
abstract class AMD64CallingConvention extends CallingConvention {

    private static int stackArgumentCount(Class<?>[] parameterTypes) {
        int floatingArgs = 0;
        for (Class<?> type : parameterTypes) {
            if (type == float.class || type == double.class) floatingArgs++;
        }
        // Native-ABI shuffles validate references before we get here. Naked
        // Java-ABI targets expect exactly the caller's original oop in its slot.
        return Math.max(0, parameterTypes.length - floatingArgs - 6) + Math.max(0, floatingArgs - 8);
    }

    // At most 14 bytes here, 12 for the indirect call and 11 for the return.
    // With the largest shuffle (45) and entry barrier (46), this fits the
    // reusable 128-byte adapter even with the widest possible descriptor.
    private static int emitNarrowCallFrame(ByteBuffer buf, Class<?>[] parameterTypes) {
        int slots = stackArgumentCount(parameterTypes);
        if (slots == 0) {
            emit(buf, 0x4883ec08);                       // sub rsp, 8
            return 8;
        }

        // HotSpot's x86_64 SharedRuntime::java_calling_convention aligns each
        // spilled scalar to two 4-byte VMReg slots. SysV uses the same order
        // and stride. Entry rsp+8 is the first source; pre-call rsp+0 must be
        // the first destination, with rsp aligned to 16 before CALL.
        int frameBytes = (slots | 1) * Long.BYTES;
        if ((slots & 1) == 0) buf.put((byte) 0x50);    // push rax: padding above the arguments
        if (slots > 1) {
            // Even the largest JVM descriptor fits in 255 parameter units.
            buf.put((byte) 0xb0).put((byte) slots);     // mov al, slots
        }
        int loop = buf.position();
        // PUSH evaluates its source before decrementing rsp. As we copy from
        // last to first, source and destination descend in lockstep: the
        // source displacement is constant. No GP or FP argument is clobbered.
        if (frameBytes <= Byte.MAX_VALUE) {
            emit(buf, 0xff7424);                        // push qword [rsp+disp8]
            buf.put((byte) frameBytes);
        } else {
            emit(buf, 0xffb424);                        // push qword [rsp+disp32]
            buf.putInt(frameBytes);
        }
        if (slots > 1) {
            emit(buf, 0xfec8);                          // dec al
            buf.put((byte) 0x75);                       // jnz loop
            buf.put((byte) (loop - (buf.position() + 1)));
        }
        return frameBytes;
    }

    private static void emitNarrowReturn(ByteBuffer buf, Class<?> returnType, int frameBytes) {
        if (frameBytes <= Byte.MAX_VALUE) {
            emit(buf, 0x4883c4);                        // add rsp, imm8
            buf.put((byte) frameBytes);
        } else {
            emit(buf, 0x4881c4);                        // add rsp, imm32
            buf.putInt(frameBytes);
        }
        emitAMD64ReturnNormalization(buf, returnType);
        buf.put((byte) 0xc3);                           // ret
    }

    @Override
    void emitCallAndNormalize(
            ByteBuffer buf, long address, Class<?> returnType, Class<?>[] parameterTypes) {
        int frameBytes = emitNarrowCallFrame(buf, parameterTypes);
        buf.putShort((short) 0xb848).putLong(address);  // mov rax, address
        buf.putShort((short) 0xd0ff);                   // call rax
        emitNarrowReturn(buf, returnType, frameBytes);
    }

    @Override
    int emitDirectCallAndNormalize(
            ByteBuffer buf, Class<?> returnType, Class<?>[] parameterTypes) {
        int frameBytes = emitNarrowCallFrame(buf, parameterTypes);
        int pcOffset = buf.position();
        buf.put((byte) 0xE8).putInt(0);                 // call rel32 — JVMCI patches disp32
        emitNarrowReturn(buf, returnType, frameBytes);
        return pcOffset;
    }
}
