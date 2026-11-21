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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Complete, register-only scalar leaves. Caller verifies Linux AMD64 SysV and owns the entry barrier. */
final class AMD64ScalarLeaf {
    static final int MAX_PREFIX_BYTES = 15; // ENDBR64 + movq + negq + cmovsq + ret

    // Each column is one exact instruction, written in execution byte order. The only
    // 64-bit variant of each instruction we accept adds REX.W (including every MOV).
    private enum Ins {
        //           SysV        HotSpot       Intel syntax: result eax, x = edi/esi, y = esi/edx
        SUM(         0x8d0437,   0x8d0416),   // lea eax,[x+y]
        SUM_SWAPPED( 0x8d043e,   0x8d0416),   // same LEA with base and index interchanged
        COPY_X(      0x89f8,     0x89f0),     // mov eax,x
        NEG(         0xf7d8,     0xf7d8),     // neg eax
        NOT(         0xf7d0,     0xf7d0),     // not eax
        IF_SIGN_X(   0x0f48c7,   0x0f48c6),   // cmovs eax,x
        SUB_Y(       0x29f0,     0x29d0),     // sub eax,y
        MUL_Y(       0x0fafc6,   0x0fafc2),   // imul eax,y
        AND_Y(       0x21f0,     0x21d0),     // and eax,y
        OR_Y(        0x09f0,     0x09d0),     // or eax,y
        XOR_Y(       0x31f0,     0x31d0);     // xor eax,y

        final int sysv, java;

        Ins(int sysv, int java) {
            this.sysv = sysv;
            this.java = java;
        }
    }

    private enum Shape {
        ADD(2, "add", Ins.SUM),
        ADD_SWAPPED(2, "add", Ins.SUM_SWAPPED),
        ABS(1, "abs", Ins.COPY_X, Ins.NEG, Ins.IF_SIGN_X),
        SUB(2, "sub", Ins.COPY_X, Ins.SUB_Y),
        MUL(2, "mul", Ins.COPY_X, Ins.MUL_Y),
        AND(2, "and", Ins.COPY_X, Ins.AND_Y),
        OR(2, "or", Ins.COPY_X, Ins.OR_Y),
        XOR(2, "xor", Ins.COPY_X, Ins.XOR_Y),
        NEG(1, "neg", Ins.COPY_X, Ins.NEG),
        NOT(1, "not", Ins.COPY_X, Ins.NOT),
        ID(1, "id", Ins.COPY_X);

        final int arity;
        final String operator;
        final AMD64ScalarLeaf i32, i64;

        Shape(int arity, String operator, Ins... body) {
            this.arity = arity;
            this.operator = operator;
            i32 = new AMD64ScalarLeaf(this, int.class, body);
            i64 = new AMD64ScalarLeaf(this, long.class, body);
        }
    }

    private static final Shape[] SHAPES = Shape.values();
    private final Shape shape;
    private final Class<?> type;
    private final byte[] sysvBody, javaBody;
    private MethodHandle handle;

    private AMD64ScalarLeaf(Shape shape, Class<?> type, Ins[] body) {
        this.shape = shape;
        this.type = type;
        sysvBody = encode(body, true, type == long.class);
        javaBody = encode(body, false, type == long.class);
    }

    // Assemble the constant tables once, never decode or rewrite input bytes.
    private static byte[] encode(Ins[] body, boolean sysv, boolean wide) {
        int n = 1; // RET
        for (Ins ins : body) {
            int op = sysv ? ins.sysv : ins.java;
            n += (wide ? 1 : 0) + (op > 0xffff ? 3 : 2);
        }
        byte[] bytes = new byte[n];
        int p = 0;
        for (Ins ins : body) {
            int op = sysv ? ins.sysv : ins.java;
            if (wide) bytes[p++] = 0x48;
            if (op > 0xffff) bytes[p++] = (byte) (op >>> 16);
            bytes[p++] = (byte) (op >>> 8);
            bytes[p++] = (byte) op;
        }
        bytes[p] = (byte) 0xc3;
        return bytes;
    }

    static boolean accepts(Class<?> result, Class<?>[] params) {
        return (result == int.class || result == long.class) && (params.length == 1 || params.length == 2)
                && params[0] == result && (params.length == 1 || params[1] == result);
    }

    // Both callers validated the homogeneous int/long signature and input window.
    static AMD64ScalarLeaf matchWord(byte[] code, int limit, Class<?> result, int arity) {
        int p = limit >= 4 && code[0] == (byte) 0xf3 && code[1] == 0x0f
                && code[2] == 0x1e && code[3] == (byte) 0xfa ? 4 : 0;
        for (Shape shape : SHAPES) {
            if (shape.arity != arity) continue;
            AMD64ScalarLeaf leaf = result == int.class ? shape.i32 : shape.i64;
            byte[] body = leaf.sysvBody;
            if (limit - p >= body.length && Arrays.equals(code, p, p + body.length, body, 0, body.length)) {
                return leaf; // body includes RET; any following bytes are unreachable
            }
        }
        return null;
    }

    void emitJavaBody(ByteBuffer out) {
        out.put(javaBody);
    }

    // Only the explicit intrinsic API needs method handles. Resolve one exact static
    // target on its first request; calling the returned handle does not touch this lock.
    synchronized MethodHandle handle() {
        if (handle == null) {
            MethodType signature = shape.arity == 1 ? MethodType.methodType(type, type)
                    : MethodType.methodType(type, type, type);
            try {
                handle = MethodHandles.lookup().findStatic(AMD64ScalarLeaf.class, shape.operator, signature);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e); // shape table disagrees with the operators below
            }
        }
        return handle;
    }

    private static int add(int x, int y) { return x + y; }
    private static long add(long x, long y) { return x + y; }
    private static int abs(int x) { return Math.abs(x); }
    private static long abs(long x) { return Math.abs(x); }
    private static int sub(int x, int y) { return x - y; }
    private static long sub(long x, long y) { return x - y; }
    private static int mul(int x, int y) { return x * y; }
    private static long mul(long x, long y) { return x * y; }
    private static int and(int x, int y) { return x & y; }
    private static long and(long x, long y) { return x & y; }
    private static int or(int x, int y) { return x | y; }
    private static long or(long x, long y) { return x | y; }
    private static int xor(int x, int y) { return x ^ y; }
    private static long xor(long x, long y) { return x ^ y; }
    private static int neg(int x) { return -x; }
    private static long neg(long x) { return -x; }
    private static int not(int x) { return ~x; }
    private static long not(long x) { return ~x; }
    private static int id(int x) { return x; }
    private static long id(long x) { return x; }
}
