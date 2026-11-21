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

import jdk.internal.misc.Unsafe;

import java.util.Objects;

/** Native memory primitives without the terminally deprecated sun.misc facade. */
final class NativeMemory {
    static final Unsafe unsafe = Unsafe.getUnsafe();
    // Unsafe's offset field/method changed from int to long between JDK 21 and 25.
    // Use the same VM layout query as the array-argument adapters.
    private static final long BYTE_ARRAY_BASE = CallingConvention.arrayBaseOffset(byte[].class);

    private NativeMemory() {
    }

    static void copyToArray(long address, byte[] dest, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, dest.length);
        unsafe.copyMemory(null, address, dest, BYTE_ARRAY_BASE + offset, length);
    }
}
