/* SPDX-License-Identifier: GPL-2.0-or-later */
package one.nalim;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Hot layout policies for Linux AMD64. Unidentified CPUs keep the generic layout. */
final class AMD64Dispatch {
    // A taken jump helped both core types on the measured Strix Point model.
    // Family 0x1a alone also covers unmeasured chips; leave them on fall-through.
    static final boolean SPLIT_INLINE_BODY = isStrixPoint();

    private AMD64Dispatch() {
    }

    private static boolean isStrixPoint() {
        try (BufferedReader info = Files.newBufferedReader(
                Paths.get("/proc/cpuinfo"), StandardCharsets.ISO_8859_1)) {
            boolean amd = false, family = false, model = false;
            for (String line; (line = info.readLine()) != null && !line.isEmpty(); ) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String field = line.substring(0, colon).trim();
                if (field.equals("vendor_id")) {
                    if (!line.substring(colon + 1).trim().equals("AuthenticAMD")) return false;
                    amd = true;
                } else if (field.equals("cpu family")) {
                    if (!line.substring(colon + 1).trim().equals("26")) return false;
                    family = true;
                } else if (field.equals("model")) {
                    if (!line.substring(colon + 1).trim().equals("36")) return false;
                    model = true;
                }
                if (amd && family && model) return true;
            }
        } catch (IOException | SecurityException unavailable) {
            return false;
        }
        return false;
    }
}
