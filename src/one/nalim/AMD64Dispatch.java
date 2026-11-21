/* SPDX-License-Identifier: GPL-2.0-or-later */
package one.nalim;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Hot layout policies for Linux AMD64. Unidentified CPUs keep the generic layout. */
final class AMD64Dispatch {
    // A taken jump between the shuffle and a verified leaf helped both Zen 5
    // and Zen 5c. Do not add a branch by default on unmeasured architectures.
    static final boolean SPLIT_INLINE_BODY = isZen5Family();

    private AMD64Dispatch() {
    }

    private static boolean isZen5Family() {
        try (BufferedReader info = Files.newBufferedReader(
                Paths.get("/proc/cpuinfo"), StandardCharsets.ISO_8859_1)) {
            boolean amd = false, family = false;
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
                }
                if (amd && family) return true;
            }
        } catch (IOException | SecurityException unavailable) {
            return false;
        }
        return false;
    }
}
