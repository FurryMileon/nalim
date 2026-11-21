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

/** Two-pass @Code decoder: size the final buffer, then fill it without an intermediate copy. */
final class HexCode {
    private HexCode() {
    }

    // The default (non-Unicode) Java regex \s matched precisely these six characters.
    private static boolean space(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r');
    }

    static int decodedLength(String hex) {
        int n = hex.length();
        int digits = n;
        for (int i = 0; i < n; i++) {
            if (space(hex.charAt(i))) digits--;
        }
        // Check parity before any digits: odd malformed strings historically threw this
        // exception, whereas an invalid pair of an even-length string threw NumberFormatException.
        if ((digits & 1) != 0) throw new IllegalArgumentException("Invalid hex string");
        return digits >>> 1;
    }

    /** Called after decodedLength; digit errors abandon the uninstalled destination. */
    static void emit(ByteBuffer dst, String hex) {
        int n = hex.length();
        for (int i = 0; i < n;) {
            char high = hex.charAt(i++);
            if (space(high)) continue;
            char low;
            do {
                low = hex.charAt(i++);
            } while (space(low));
            int h = Character.digit(high, 16);
            int l = Character.digit(low, 16);
            int value = (h << 4) | l;
            if (value < 0) {
                // Integer.parseInt(pair, 16) also accepted a sign in the high position.
                if (l < 0 || (high != '+' && high != '-')) {
                    throw new NumberFormatException("Invalid hex byte: " + high + low);
                }
                value = high == '-' ? -l : l;
            }
            dst.put((byte) value);
        }
    }
}
