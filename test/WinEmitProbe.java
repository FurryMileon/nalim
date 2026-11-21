package one.nalim;

import java.io.FileOutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Emits Windows-x64 adapters for the Linux execution harness (win_abi_harness.c).
 * Native callees use ms_abi; naked targets consume the original Java arguments.
 * The harness patches 0xDEADBEEFCAFEBABE to the address of each callee.
 */
public class WinEmitProbe {

    static final long MAGIC = 0xDEADBEEFCAFEBABEL;

    static void emit(String name, Class<?> ret, Class<?>... types) throws Exception {
        emit(name, ret, types, null);
    }

    static void emit(String name, Class<?> ret, Class<?>[] types, Annotation[][] annotations) throws Exception {
        AMD64WindowsCallingConvention cc = new AMD64WindowsCallingConvention();
        ByteBuffer buf = ByteBuffer.allocate(1024).order(ByteOrder.nativeOrder());
        cc.emitFramedCall(buf, MAGIC, ret, types, annotations);
        write(name, buf);
    }

    static void write(String name, ByteBuffer buf) throws Exception {
        try (FileOutputStream out = new FileOutputStream("win_adapter_" + name + ".bin")) {
            out.write(buf.array(), 0, buf.position());
        }
        System.out.println(name + ": " + buf.position() + " bytes");
    }

    static class Box {
        long first;
        long selected;
    }

    // Register, stack-from-register and stack-from-Java-stack field addresses.
    static native long fields(long a, float b, @FieldOffset("selected") Box c, long d,
                              @FieldOffset("selected") Box e, double f, long g, double h,
                              float i, double j, float k, double l, long m,
                              @FieldOffset("selected") Box n, float o, @FieldOffset("selected") Box p,
                              double q, float r);

    public static void main(String[] args) throws Exception {
        Class<?> f = float.class, d = double.class, i = int.class, l = long.class;

        emit("iii", i, i, i, i);
        emit("fff", f, f, f, f);
        emit("fii", i, f, i, i);                       // lerp_int shape
        emit("fffi", i, f, f, f, i);                   // hsv_to_argb shape
        emit("f5", f, f, f, f, f, f);                  // catmull_rom / map_float shape
        emit("d5", d, d, d, d, d, d);                  // map_double shape
        emit("d6", d, d, d, d, d, d, d);               // lerp_2 shape
        emit("d11", d, d, d, d, d, d, d, d, d, d, d, d); // lerp_3 shape
        emit("l7", l, l, l, l, l, l, l, l);            // 7 longs: java stack int source
        emit("mixed10", d, i, d, l, d, i, d, l, d, i, d);
        emit("if4", d, i, f, f, f, f);                 // trailing fp on native stack
        emit("shadow", l);                             // callee writes its entire shadow space
        emit("ffll", l, f, f, l, l);                   // r8 must reach r9 before rdx overwrites r8

        // Exercise the direct form even if the actual DLL and JVM code cache
        // happen to be more than 2 GiB apart during the native-platform test.
        AMD64WindowsCallingConvention cc = new AMD64WindowsCallingConvention();
        ByteBuffer direct = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
        int call = cc.emitDirectCallAndNormalize(direct, short.class, new Class<?>[]{i, i, i, i, i, i});
        direct.putInt(call + 1, direct.position() - (call + 5));
        cc.emitCall(direct, MAGIC); // local rel32 target: tail-jump to the assembly fixture
        write("naked_direct", direct);
        Method fields = WinEmitProbe.class.getDeclaredMethod("fields", l, f, Box.class, l, Box.class, d,
                l, d, f, d, f, d, l, Box.class, f, Box.class, d, f);
        emit("fields", l, fields.getParameterTypes(), fields.getParameterAnnotations());
        // Object layouts vary by VM. The runner checks the full adjusted pointer values.
        Files.writeString(Path.of("win_field_offset.txt"),
                Integer.toString(CallingConvention.fieldOffset(Box.class, "selected")));
    }
}
