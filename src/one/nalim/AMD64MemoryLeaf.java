/* SPDX-License-Identifier: GPL-2.0-or-later */
package one.nalim;

import java.nio.ByteBuffer;

/** Replace a validated SysV shuffle and an exact MOV+RET with a Java-ABI MOV+RET. */
final class AMD64MemoryLeaf {
    private AMD64MemoryLeaf() {
    }

    static boolean tryEmit(ByteBuffer out, int shuffleStart, byte[] code, int limit,
                           Class<?> result, Class<?>[] types) {
        if (limit < 3 || limit > code.length) return false;
        int p = 0;
        if (code[0] == (byte) 0xf3) {
            if (limit < 7 || code[1] != 0x0f || code[2] != 0x1e || code[3] != (byte) 0xfa) return false;
            p = 4;  // ENDBR64
        }
        boolean wide = code[p] == 0x48;  // only REX.W (no register extensions)
        if (wide) p++;
        if (limit - p < 3) return false;
        byte op = code[p++];
        int mr = code[p++] & 0xff;
        Class<?> width = wide ? long.class : int.class;
        int reg;
        if (op == (byte) 0x8b) {
            // mov eax/rax, [rdi+disp]; result stays in eax/rax
            if ((mr & 0x3f) != 0x07 || types.length != 1 || result != width) return false;
            reg = 0;
        } else if (op == (byte) 0x89) {
            // mov [rdi+disp], esi/rsi; value comes from edx/rdx in Java
            if ((mr & 0x3f) != 0x37 || types.length != 2 || result != void.class || types[1] != width) return false;
            reg = 2;
        } else {
            return false;
        }
        int disp;
        switch (mr >>> 6) {
            case 0: disp = 0; break;
            case 1:
                if (limit - p < 2) return false;
                disp = code[p++];
                break;
            case 2:
                if (limit - p < 5) return false;
                disp = (code[p] & 0xff) | (code[p + 1] & 0xff) << 8
                        | (code[p + 2] & 0xff) << 16 | code[p + 3] << 24;
                p += 4;
                break;
            default: return false;  // register, not memory
        }
        if (code[p] != (byte) 0xc3) return false;

        if (shuffleStart < 0 || shuffleStart > out.position()) return false;
        if (types[0] != long.class) {
            if (types[0].isPrimitive()) return false;
            // Reuse the *actual* array/annotated-field baseOffset already resolved by
            // javaToNative, rather than repeating its JVMCI lookup. Only accept its
            // lea rdi,[rsi+disp8/32] encoding; future unknown shuffles fall back.
            int len = out.position() - shuffleStart;
            if (len < 4 || out.get(shuffleStart) != 0x48 || out.get(shuffleStart + 1) != (byte) 0x8d) return false;
            byte lea = out.get(shuffleStart + 2);
            int base;
            if (lea == 0x7e) base = out.get(shuffleStart + 3);
            else if (lea == (byte) 0xbe && len >= 7) base = out.getInt(shuffleStart + 3);
            else return false;
            if (base < 0 || disp > Integer.MAX_VALUE - base) return false;
            disp += base;
        }

        int dispBytes = disp == 0 ? 0 : disp == (byte) disp ? 1 : 4;
        if (out.limit() - shuffleStart < (wide ? 4 : 3) + dispBytes) return false;
        out.position(shuffleStart);
        if (wide) out.put((byte) 0x48);
        int mod = dispBytes == 0 ? 0 : dispBytes == 1 ? 0x40 : 0x80;
        out.put(op).put((byte) (mod | reg << 3 | 6));  // [rsi+disp]
        if (dispBytes == 1) out.put((byte) disp);
        else if (dispBytes == 4) out.putInt(disp);
        out.put((byte) 0xc3);
        return true;
    }
}
