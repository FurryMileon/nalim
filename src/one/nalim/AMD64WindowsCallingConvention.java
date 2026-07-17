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

class AMD64WindowsCallingConvention extends CallingConvention {

    // x64 calling convention (Windows):
    //     Java: rdx,  r8,  r9, rdi, rsi, rcx, stack
    //   Native: rcx, rdx,  r8,  r9, stack

    private static final int[] MOVE_INT_ARG = {
            0x89d1,    // mov  ecx, edx
            0x4489c2,  // mov  edx, r8d
            0x4589c8,  // mov  r8d, r9d
            0x4189f9,  // mov  r9d, edi
    };

    private static final int[] MOVE_LONG_ARG = {
            0x4889d1,  // mov  rcx, rdx
            0x4c89c2,  // mov  rdx, r8
            0x4d89c8,  // mov  r8, r9
            0x4989f9,  // mov  r9, rdi
    };

    private static final int[] MOVE_OBJ_ARG = {
            0x488d4a,  // lea  rcx, [rdx+N]
            0x498d50,  // lea  rdx, [r8+N]
            0x4d8d41,  // lea  r8, [r9+N]
            0x4c8d4f,  // lea  r9, [rdi+N]
    };

    @Override
    public void javaToNative(ByteBuffer buf, Class<?>[] types, Annotation[][] annotations) {
        int index = 0;
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            if (type == float.class || type == double.class) {
                continue;
            }
            if (index >= 4) {
                throw new IllegalArgumentException("At most 4 integer arguments are supported");
            } else if (type.isPrimitive()) {
                emit(buf, (type == long.class ? MOVE_LONG_ARG : MOVE_INT_ARG)[index++]);
            } else {
                emitLea(buf, MOVE_OBJ_ARG[index++], baseOffset(type, annotations[i]));
            }
        }
    }

    @Override
    public void emitCall(ByteBuffer buf, long address) {
        buf.putShort((short) 0xb848).putLong(address);  // mov rax, address
        buf.putShort((short) 0xe0ff);                   // jmp rax
    }

    @Override
    void emitCallAndNormalize(
            ByteBuffer buf, long address, Class<?> returnType, Class<?>[] parameterTypes) {
        if (parameterTypes.length > 4) {
            throw new IllegalArgumentException(
                    "Narrow return with native stack arguments is not supported");
        }

        // 32-byte Win64 shadow space plus 8 bytes for call-site alignment.
        buf.put((byte) 0x48).put((byte) 0x83).put((byte) 0xEC).put((byte) 0x28);
        buf.putShort((short) 0xb848).putLong(address);  // mov rax, address
        buf.putShort((short) 0xd0ff);                   // call rax
        buf.put((byte) 0x48).put((byte) 0x83).put((byte) 0xC4).put((byte) 0x28);
        emitAMD64ReturnNormalization(buf, returnType);
        buf.put((byte) 0xC3);                          // ret
    }

    @Override
    int emitDirectCallAndNormalize(
            ByteBuffer buf, Class<?> returnType, Class<?>[] parameterTypes) {
        if (parameterTypes.length > 4) {
            throw new IllegalArgumentException(
                    "Narrow return with native stack arguments is not supported");
        }

        buf.put((byte) 0x48).put((byte) 0x83).put((byte) 0xEC).put((byte) 0x28);
        int pcOffset = buf.position();
        buf.put((byte) 0xE8).putInt(0);                 // call rel32 — JVMCI patches disp32
        buf.put((byte) 0x48).put((byte) 0x83).put((byte) 0xC4).put((byte) 0x28);
        emitAMD64ReturnNormalization(buf, returnType);
        buf.put((byte) 0xC3);                          // ret
        return pcOffset;
    }

    @Override
    int emitNMethodBarrier(ByteBuffer buf) {
        // Same sequence as Linux x64 — r15 is the JavaThread register on
        // HotSpot Windows-x64 as well, and the barrier opcode is OS-agnostic.
        while ((buf.position() & 3) != 0) {
            buf.put((byte) 0x90);                       // nop
        }
        int markOffset = buf.position();
        // cmp dword ptr [r15 + 0], 0x00000000  (see AMD64LinuxCallingConvention)
        buf.put((byte) 0x41).put((byte) 0x81).put((byte) 0x7F).put((byte) 0x00);
        buf.putInt(0);
        return markOffset;
    }
}
