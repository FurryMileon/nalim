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

/** Native memory primitives without the terminally deprecated sun.misc facade. */
final class NativeMemory {
    static final Unsafe unsafe = Unsafe.getUnsafe();

    private NativeMemory() {
    }
}
