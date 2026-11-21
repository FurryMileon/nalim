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

    // Win64 native convention is POSITIONAL: argument i (of any class) uses
    // slot i — integer args in rcx,rdx,r8,r9, FP args in xmm0-xmm3, and every
    // argument from position 4 on goes to the stack at [rsp+32+8*(i-4)],
    // above the 32-byte shadow space the CALLER must reserve.
    //
    // HotSpot's compiled Java convention is PER-CLASS (like System V):
    //     ints: rdx, r8, r9, rdi, rsi, rcx, then stack
    //       fp: xmm0-xmm7, then stack
    // Java stack args are spilled in signature order, one 8-byte slot each,
    // the first at [rsp+8] on entry (above the return address).
    //
    // The two schemes only coincide for signatures whose argument classes
    // never interleave and fit in 4 slots. Everything else needs a shuffle,
    // and any call with 5+ args needs native stack stores. Since the native
    // callee also owns the shadow space, a tail jump would let it scribble
    // over the Java caller's frame — so every non-naked Windows link goes
    // through a real frame: sub rsp, N / call / add rsp, N / ret.

    private static final int RAX = 0, RCX = 1, RDX = 2, RSI = 6, RDI = 7, R8 = 8, R9 = 9;

    private static final int[] JAVA_INT_REGS = {RDX, R8, R9, RDI, RSI, RCX};
    private static final int[] NATIVE_INT_REGS = {RCX, RDX, R8, R9};

    private static boolean isFp(Class<?> type) {
        return type == float.class || type == double.class;
    }

    private static int countFp(Class<?>[] types, int regArgs) {
        int fp = 0;
        for (int i = 0; i < regArgs; i++) {
            if (isFp(types[i])) fp++;
        }
        return fp;
    }

    private static int objectOffset(Class<?> type, Annotation[][] annotations, int p) {
        return annotations != null && !type.isPrimitive() ? baseOffset(type, annotations[p]) : 0;
    }

    @Override
    public void javaToNative(ByteBuffer buf, Class<?>[] types, Annotation[][] annotations) {
        int regArgs = Math.min(types.length, 4);
        emitRegisterMoves(buf, types, annotations, regArgs, countFp(types, regArgs));
    }

    private static void emitRegisterMoves(ByteBuffer buf, Class<?>[] types, Annotation[][] annotations,
                                          int regArgs, int fp) {
        // For an integer at position p with f preceding FP args, its Java GPR
        // is at Win64 index p-f+1 (extend the Win64 list by rdi at index 4).
        // After the first FP, this index is <= p. FP sources are also <= p.
        // Both mappings increase within their class: walk this suffix backward.
        int p = regArgs;
        while (fp != 0) {
            Class<?> type = types[--p];
            if (isFp(type)) {
                if (--fp != p) emitMovaps(buf, p, fp);
            } else {
                emitIntegerArg(buf, type, annotations, p, JAVA_INT_REGS[p - fp]);
            }
        }
        // Remaining leading integers come from index i+1; move them forward.
        // No suffix destination overlaps these sources (the first FP is at p).
        for (int i = 0; i < p; i++) {
            emitIntegerArg(buf, types[i], annotations, i, JAVA_INT_REGS[i]);
        }
    }

    private static void emitIntegerArg(ByteBuffer buf, Class<?> type, Annotation[][] annotations, int p, int src) {
        int dst = NATIVE_INT_REGS[p];
        int offset = objectOffset(type, annotations, p);
        if (offset != 0) {
            emitLea64(buf, dst, src, offset);  // also needed for an in-place field address
        } else if (src != dst) {
            emitMovReg(buf, dst, src, type == long.class || !type.isPrimitive());
        }
    }

    @Override
    boolean requiresFramedCall(Class<?>[] parameterTypes) {
        // Every Win64 callee owns [rsp+8, rsp+40) — the shadow space its
        // caller must reserve. A tail jump would hand it the Java caller's
        // frame instead, so all non-naked links call through a real frame.
        return true;
    }

    @Override
    void emitFramedCall(ByteBuffer buf, long address, Class<?> returnType,
                        Class<?>[] types, Annotation[][] annotations) {
        int frame = emitFramePrologue(buf, types, annotations);
        buf.putShort((short) 0xb848).putLong(address);  // mov rax, address
        buf.putShort((short) 0xd0ff);                   // call rax
        emitFrameEpilogue(buf, frame, returnType);
    }

    @Override
    int emitDirectFramedCall(ByteBuffer buf, Class<?> returnType,
                             Class<?>[] types, Annotation[][] annotations) {
        int frame = emitFramePrologue(buf, types, annotations);
        int pcOffset = buf.position();
        buf.put((byte) 0xE8).putInt(0);                 // call rel32 — JVMCI patches disp32
        emitFrameEpilogue(buf, frame, returnType);
        return pcOffset;
    }

    // Full marshalling with a frame: sub rsp, N; store native stack args
    // (positions >= 4, argument registers still pristine); then the register
    // shuffle for positions 0-3. Entry RSP % 16 == 8 (nmethod entry, after the
    // caller's push of the return address), so the frame keeps N % 16 == 8 to
    // leave the nested call site 16-byte aligned.
    private int emitFramePrologue(ByteBuffer buf, Class<?>[] types, Annotation[][] annotations) {
        int stackArgs = Math.max(0, types.length - 4);
        int frame = 32 + 8 * stackArgs;
        if ((frame & 15) != 8) {
            frame += 8;
        }
        emitRspAdjust(buf, 0xEC, frame);                // sub rsp, frame

        int regArgs = Math.min(types.length, 4);
        int regFp = countFp(types, regArgs);
        int fp = regFp, gp = regArgs - regFp, spillOff = frame + 8;
        for (int p = 4; p < types.length; p++) {
            Class<?> type = types[p];
            int natOff = 8 * p;  // first four slots are the shadow space
            boolean floating = isFp(type);
            if (floating && fp < 8) {
                emitMovssSdToRsp(buf, natOff, fp++, type == float.class);
                continue;
            }

            boolean wide = type == long.class || type == double.class || !type.isPrimitive();
            int src;
            if (!floating && gp < JAVA_INT_REGS.length) {
                src = JAVA_INT_REGS[gp++];
            } else {
                emitMovRaxFromRsp(buf, spillOff, wide);
                spillOff += 8;
                src = RAX;
            }
            int offset = objectOffset(type, annotations, p);
            if (offset != 0) {
                emitLea64(buf, RAX, src, offset);
                src = RAX;
            }
            emitMovToRsp(buf, natOff, src, wide);
        }
        emitRegisterMoves(buf, types, annotations, regArgs, regFp);
        return frame;
    }

    private void emitFrameEpilogue(ByteBuffer buf, int frame, Class<?> returnType) {
        emitRspAdjust(buf, 0xC4, frame);                // add rsp, frame
        if (needsReturnNormalization(returnType)) {
            emitAMD64ReturnNormalization(buf, returnType);
        }
        buf.put((byte) 0xC3);                           // ret
    }

    // ── encoding helpers ────────────────────────────────────────────────

    // mov dst, src (register to register, 32- or 64-bit)
    private static void emitMovReg(ByteBuffer buf, int dst, int src, boolean wide) {
        int rex = (wide ? 0x48 : 0x40) | ((src & 8) >> 1) | ((dst & 8) >> 3);
        if (rex != 0x40) buf.put((byte) rex);
        buf.put((byte) 0x89);
        buf.put((byte) (0xC0 | ((src & 7) << 3) | (dst & 7)));
    }

    // movaps xmm_dst, xmm_src (indices 0-7 only — argument registers)
    private static void emitMovaps(ByteBuffer buf, int dst, int src) {
        buf.put((byte) 0x0F).put((byte) 0x28);
        buf.put((byte) (0xC0 | (dst << 3) | src));
    }

    // lea dst, [src + disp] (64-bit)
    private static void emitLea64(ByteBuffer buf, int dst, int src, int disp) {
        if (disp < 0) {
            throw new IllegalArgumentException("Negative object offset: " + disp);
        }
        buf.put((byte) (0x48 | ((dst & 8) >> 1) | ((src & 8) >> 3)));
        buf.put((byte) 0x8D);
        boolean byteDisp = disp <= Byte.MAX_VALUE;
        buf.put((byte) ((byteDisp ? 0x40 : 0x80) | ((dst & 7) << 3) | (src & 7)));
        if ((src & 7) == 4) {
            buf.put((byte) 0x24);  // SIB for rsp/r12 base
        }
        if (byteDisp) {
            buf.put((byte) disp);
        } else {
            buf.putInt(disp);
        }
    }

    // sub/add rsp, imm (opcodeExt 0xEC = sub, 0xC4 = add)
    private static void emitRspAdjust(ByteBuffer buf, int modrm, int imm) {
        if (imm <= Byte.MAX_VALUE) {
            buf.put((byte) 0x48).put((byte) 0x83).put((byte) modrm).put((byte) imm);
        } else {
            buf.put((byte) 0x48).put((byte) 0x81).put((byte) modrm).putInt(imm);
        }
    }

    private static void putRspModrm(ByteBuffer buf, int reg, int disp) {
        boolean byteDisp = disp <= Byte.MAX_VALUE;
        buf.put((byte) ((byteDisp ? 0x40 : 0x80) | ((reg & 7) << 3) | 4));
        buf.put((byte) 0x24);                           // SIB: base = rsp
        if (byteDisp) {
            buf.put((byte) disp);
        } else {
            buf.putInt(disp);
        }
    }

    // mov [rsp+disp], reg (32- or 64-bit)
    private static void emitMovToRsp(ByteBuffer buf, int disp, int reg, boolean wide) {
        int rex = (wide ? 0x48 : 0x40) | ((reg & 8) >> 1);
        if (rex != 0x40) buf.put((byte) rex);
        buf.put((byte) 0x89);
        putRspModrm(buf, reg, disp);
    }

    // mov rax/eax, [rsp+disp]
    private static void emitMovRaxFromRsp(ByteBuffer buf, int disp, boolean wide) {
        if (wide) buf.put((byte) 0x48);
        buf.put((byte) 0x8B);
        putRspModrm(buf, RAX, disp);
    }

    // movss/movsd [rsp+disp], xmm (indices 0-7)
    private static void emitMovssSdToRsp(ByteBuffer buf, int disp, int xmm, boolean single) {
        buf.put((byte) (single ? 0xF3 : 0xF2)).put((byte) 0x0F).put((byte) 0x11);
        putRspModrm(buf, xmm, disp);
    }

    @Override
    public void emitCall(ByteBuffer buf, long address) {
        // Only reached for naked = true (Java-ABI target): a tail jump is
        // correct there — no argument shuffle, no shadow-space ownership.
        buf.putShort((short) 0xb848).putLong(address);  // mov rax, address
        buf.putShort((short) 0xe0ff);                   // jmp rax
    }

    @Override
    void emitCallAndNormalize(
            ByteBuffer buf, long address, Class<?> returnType, Class<?>[] parameterTypes) {
        // Naked narrow-return targets only (framed path handles the rest).
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
