package one.nalim;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class NativeMappingsTest {
    public static void main(String[] args) throws Exception {
        byte[] valid = ascii("1000-2000 r-xp 0 00:00 0 /a path\n"
                + "2000-3000 r--p 0 00:00 0 /" + "x".repeat(9000) + "\n"
                + "3000-4000 rwxp 0 00:00 0\n"
                + "9000000000000000-fffffffffffffff0 r-xs");
        long[] expected = {0x1000, 0x2000, 1, 0x2000, 0x3000, 0, 0x9000000000000000L, -16L, 1};
        // The bad line exceeds the carry prefix by one byte. No earlier VMA
        // may be returned as an executable proof after a truncated header.
        byte[] malformed = ascii("1000-2000 r-xp\n10000000000000000-fffffffffffffff0 r-xp\n");
        for (int chunk = 1; chunk <= 80; chunk++) {
            if (!Arrays.equals(expected, NativeMappings.readRanges(stream(valid, chunk)))) {
                throw new AssertionError("wrong VMA snapshot with chunk size " + chunk);
            }
            try {
                if (NativeMappings.readRanges(stream(malformed, chunk)).length != 0) {
                    throw new AssertionError("partial VMA proof with chunk size " + chunk);
                }
            } catch (NumberFormatException rejectedByAddressParser) {
                // The procfs reader also rejects the entire snapshot in this case.
            }
        }
        System.out.println("NativeMappingsTest PASS");
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static InputStream stream(byte[] bytes, int chunk) {
        return new ByteArrayInputStream(bytes) {
            @Override
            public int read(byte[] buffer, int start, int length) {
                return super.read(buffer, start, Math.min(length, chunk));
            }
        };
    }
}
