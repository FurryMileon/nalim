package one.nalim;

import java.lang.annotation.Annotation;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

// Run on Linux AMD64, from repo root with JAR pointing at the candidate jar:
// D=$(mktemp -d)
// cc -O2 -shared -fPIC test/nalim_mem.c test/nalim_memory_leaves.S -o "$D/libnalim_memory_leaves.so"
// "$JAVA_HOME/bin/javac" --release 11 -cp "$JAR" -d "$D" test/MemoryLeafTest.java
// "$JAVA_HOME/bin/java" -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI --add-modules=jdk.internal.vm.ci \
//   --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED \
//   -Djava.library.path="$D" -Dnalim.dump="$D/dump" -cp "$JAR:$D" one.nalim.MemoryLeafTest
// Omit -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI on JDK 25.
public class MemoryLeafTest {
    @Link(name="mem_load_long") static native long rawLoad(long p);
    @Link(name="mem_store_long") static native void rawStore(long p, long v);
    @Link(name="mem_load_long") static native long arrayLoad(long[] p);
    @Link(name="mem_store_long") static native void arrayStore(long[] p, long v);
    @Link(name="mem_load_long") static native long fieldLoad(@FieldOffset("value") Fields p);
    @Link(name="mem_store_long") static native void fieldStore(@FieldOffset("value") Fields p, long v);
    @Link(name="ml_load_previous") static native int previous(long p);
    @Link(name="ml_store_previous") static native void putPrevious(long p, int v);
    @Link(name="ml_load_far") static native int far(byte[] p);
    @Link(name="ml_store_far") static native void putFar(byte[] p, int v);
    @Link(name="ml_store_twice") static native void twice(int[] p, int v);
    @Link(name="ml_load_index") static native int index(int[] p, int i);
    @Link(name="ml_load_literal") static native int literal(long ignored);

    // Inheritance forces the subclass field past the signed-byte displacement range.
    static class Padding {
        long p0,p1,p2,p3,p4,p5,p6,p7,p8,p9,p10,p11,p12,p13,p14,p15;
    }
    static final class Fields extends Padding { long left, value, right; }

    public static void main(String[] args) throws Exception {
        Linker.loadLibrary("nalim_memory_leaves");
        Linker.linkClass(MemoryLeafTest.class);
        raw();
        heap();
        check(twiceAndIndex() && literal(0) == 0x23456789, "near-miss calls");
        declines();
        // Verify the real installed code, not merely that the generic inliner accepted it.
        if (System.getProperty("nalim.dump") != null) {
            body("rawLoad", bytes(0x48, 0x8b, 0x06, 0xc3));
            body("rawStore", bytes(0x48, 0x89, 0x16, 0xc3));
            body("previous", bytes(0x8b, 0x46, 0xfc, 0xc3));
            body("putPrevious", bytes(0x89, 0x56, 0xfc, 0xc3));
            int field = CallingConvention.fieldOffset(Fields.class, "value");
            check(field > 127, "large field offset");
            body("fieldStore", disp32(bytes(0x48, 0x89, 0x96), field));
            body("putFar", disp32(bytes(0x89, 0x96), 129 + CallingConvention.arrayBaseOffset(byte[].class)));
            body("arrayLoad", bytes(0x48, 0x8b, 0x46, CallingConvention.arrayBaseOffset(long[].class), 0xc3));
        }
        System.out.println("MemoryLeafTest passed");
    }

    private static void raw() {
        long p = Linker.findAddress("ml_test_buffer");
        check(p != 0, "data address");
        long left = 0x1122334455667788L, right = 0x7766554433221100L;
        check(rawLoad(p + 8) == 0x0123456789abcdefL && previous(p + 16) == 0x01234567, "raw loads");
        rawStore(p + 8, 0x89abcdef12345678L);
        check(rawLoad(p + 8) == 0x89abcdef12345678L, "raw long store");
        putPrevious(p + 16, 0xdeadbeef);
        check(rawLoad(p + 8) == 0xdeadbeef12345678L && rawLoad(p) == left && rawLoad(p + 16) == right,
                "negative disp8, int store and neighboring bytes");
    }

    private static void heap() {
        long[] longs = {-13, -14};
        check(arrayLoad(longs) == -13, "array load");
        arrayStore(longs, 0x123456789abcdef0L);
        check(longs[0] == 0x123456789abcdef0L && longs[1] == -14, "array store");
        Fields fields = new Fields();
        fields.left = 3; fields.value = -1; fields.right = 5;
        check(fieldLoad(fields) == -1, "field load");
        fieldStore(fields, 0xfedcba9876543210L);
        check(fields.left == 3 && fields.value == 0xfedcba9876543210L && fields.right == 5, "field store");
        byte[] data = new byte[144];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        check(far(data) == 0x84838281, "positive unaligned disp32");
        putFar(data, 0xa0b0c0d0);
        for (int i = 0; i < data.length; i++) {
            int expected = i < 129 || i >= 133 ? i : 0xa0b0c0d0 >>> (8 * (i - 129));
            check(data[i] == (byte) expected, "array store at " + i);
        }
    }

    private static boolean twiceAndIndex() {
        int[] a = {11, 13, 17};
        twice(a, 19);
        return a[0] == 19 && a[1] == 19 && index(a, 2) == 17;
    }

    private static byte[] disp32(byte[] prefix, int disp) {
        return ByteBuffer.allocate(prefix.length + 5).order(ByteOrder.LITTLE_ENDIAN)
                .put(prefix).putInt(disp).put((byte) 0xc3).array();
    }

    private static void body(String name, byte[] expected) throws Exception {
        byte[] code = Files.readAllBytes(Path.of(System.getProperty("nalim.dump"), "MemoryLeafTest." + name + ".bin"));
        // The fast path starts at fallthrough from the installed GC barrier's cmp; jne rel32.
        for (int i = 0; i + 14 + expected.length <= code.length; i++) {
            if (code[i] == 0x41 && code[i+1] == (byte) 0x81 && code[i+2] == 0x7f
                    && code[i+8] == 0x0f && code[i+9] == (byte) 0x85) {
                check(Arrays.equals(code, i+14, i+14+expected.length, expected, 0, expected.length), name + " fused bytes");
                return;
            }
        }
        throw new AssertionError(name + " missing barrier");
    }

    private static void declines() {
        Class<?>[] load = {long.class};
        byte[] far = bytes(0xf3,0x0f,0x1e,0xfa,0x48,0x8b,0x87,0x11,0x22,0x33,0x44,0xc3);
        for (int limit = -1; limit < far.length; limit++) reject(far, limit, long.class, load);
        reject(far, far.length + 1, long.class, load);
        reject(bytes(0x48,0x8b,0x07,0x90,0xc3), 5, long.class, load); // not terminal
        reject(bytes(0x48,0x8b,0x05,0,0,0,0,0xc3), 8, long.class, load); // RIP-relative
        reject(bytes(0x48,0x8b,0x04,0xb7,0xc3), 5, long.class, load); // indexed
        reject(bytes(0x48,0x8b,0xc7,0xc3), 4, long.class, load); // register source
        reject(bytes(0x48,0x8b,0x17,0xc3), 4, long.class, load); // not rax
        reject(bytes(0x66,0x8b,0x07,0xc3), 4, int.class, load); // partial load
        reject(bytes(0x8b,0x07,0xc3), 3, long.class, load); // width mismatch
        reject(bytes(0x48,0x8b,0x07,0xc3), 4, long.class, int.class); // not a pointer
        reject(bytes(0x89,0x37,0xc3), 3, void.class, long.class, long.class); // value width
        reject(bytes(0x89,0x17,0xc3), 3, void.class, long.class, int.class); // wrong value register
        reject(bytes(0x48,0x8b,0x87,0xff,0xff,0xff,0x7f,0xc3), 8, long.class, long[].class); // disp32 + base overflows
    }

    private static void reject(byte[] code, int limit, Class<?> result, Class<?>... types) {
        ByteBuffer out = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        Arrays.fill(out.array(), (byte) 0xab);
        out.position(9);
        Annotation[][] annotations = new Annotation[types.length][0];
        new AMD64LinuxCallingConvention().javaToNative(out, types, annotations);
        int pos = out.position();
        byte[] saved = out.array().clone();
        check(!AMD64MemoryLeaf.tryEmit(out, 9, code, limit, result, types), "accepted " + Arrays.toString(code));
        check(pos == out.position() && Arrays.equals(saved, out.array()), "decline modified shuffle");
    }

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) b[i] = (byte) values[i];
        return b;
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }
}
