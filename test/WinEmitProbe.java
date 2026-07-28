package one.nalim;

import java.io.FileOutputStream;
import java.lang.annotation.Annotation;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Emits the Windows-x64 framed adapters for a set of signatures into
 * win_adapter_<name>.bin files so the C harness (win_abi_harness.c) can
 * execute them on Linux against __attribute__((ms_abi)) callees.
 * The callee address is the magic 0xDEADBEEFCAFEBABE, patched by the runner.
 */
public class WinEmitProbe {

    static final long MAGIC = 0xDEADBEEFCAFEBABEL;

    static void emit(String name, Class<?> ret, Class<?>... types) throws Exception {
        AMD64WindowsCallingConvention cc = new AMD64WindowsCallingConvention();
        ByteBuffer buf = ByteBuffer.allocate(1024).order(ByteOrder.nativeOrder());
        Annotation[][] ann = new Annotation[types.length][];
        for (int i = 0; i < types.length; i++) {
            ann[i] = new Annotation[0];
        }
        cc.emitFramedCall(buf, MAGIC, ret, types, ann);
        try (FileOutputStream out = new FileOutputStream("win_adapter_" + name + ".bin")) {
            out.write(buf.array(), 0, buf.position());
        }
        System.out.println(name + ": " + buf.position() + " bytes");
    }

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
    }
}
