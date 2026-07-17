/*
 * Copyright (C) 2022 Andrei Pangin
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
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package one.nalim;

import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.runtime.JVMCI;

import java.lang.annotation.Annotation;
import java.nio.ByteBuffer;

abstract class CallingConvention {

    static CallingConvention getInstance() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (!arch.contains("64")) {
            throw new IllegalStateException("Unsupported architecture: " + arch);
        }

        if (arch.contains("aarch") || arch.contains("arm")) {
            return new AArch64CallingConvention();
        }

        if (arch.contains("riscv")) {
            return new RISCV64CallingConvention();
        }

        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("windows")) {
            return new AMD64WindowsCallingConvention();
        } else {
            return new AMD64LinuxCallingConvention();
        }
    }

    abstract void javaToNative(ByteBuffer buf, Class<?>[] types, Annotation[][] annotations);

    abstract void emitCall(ByteBuffer buf, long address);

    /**
     * Emit a real native call followed by the Java-side extension required for
     * sub-word integer returns. A tail jump cannot do this because the C ABI
     * only defines AL/AX for these types, while compiled Java consumes EAX.
     */
    void emitCallAndNormalize(
            ByteBuffer buf, long address, Class<?> returnType, Class<?>[] parameterTypes) {
        throw new IllegalArgumentException(
                "Narrow native returns are not supported on this architecture: " + returnType);
    }

    /**
     * Same as {@link #emitCallAndNormalize} but with a five-byte
     * {@code call rel32} placeholder instead of {@code movabs rax; call rax},
     * for targets JVMCI can bind directly (within signed-32-bit reach of the
     * code cache). Saves an indirect branch on the narrow-return bridge.
     *
     * @return offset of the placeholder within {@code buf} — the caller
     *         registers a JVMCI {@code Call} site there — or -1 when the
     *         architecture has no direct form and the legacy path must run.
     */
    int emitDirectCallAndNormalize(
            ByteBuffer buf, Class<?> returnType, Class<?>[] parameterTypes) {
        return -1;
    }

    protected static boolean needsReturnNormalization(Class<?> type) {
        return type == boolean.class || type == byte.class
                || type == short.class || type == char.class;
    }

    protected static void emitAMD64ReturnNormalization(ByteBuffer buf, Class<?> type) {
        if (type == boolean.class) {
            // movzx eax, al
            buf.put((byte) 0x0F).put((byte) 0xB6).put((byte) 0xC0);
        } else if (type == byte.class) {
            // movsx eax, al
            buf.put((byte) 0x0F).put((byte) 0xBE).put((byte) 0xC0);
        } else if (type == short.class) {
            // movsx eax, ax
            buf.put((byte) 0x0F).put((byte) 0xBF).put((byte) 0xC0);
        } else if (type == char.class) {
            // movzx eax, ax
            buf.put((byte) 0x0F).put((byte) 0xB7).put((byte) 0xC0);
        } else {
            throw new IllegalArgumentException("Not a narrow return type: " + type);
        }
    }

    /**
     * Append the "no-op" nmethod entry barrier required by JVMCI since JDK 21.
     * Loom / JEP 444 made {@code BarrierSetNMethod} mandatory for every GC, so
     * {@code CompilerToVM.installCode0} now rejects any nmethod missing a Mark
     * with id {@code CodeInstaller::ENTRY_BARRIER_PATCH}.
     * <p>
     * The barrier is emitted AFTER the tail-call produced by {@link #emitCall},
     * so the CPU never executes it; it exists solely to satisfy JVMCI validation.
     * Implementations must pad to the architectural alignment required for
     * atomic patching (4 bytes on AMD64 / AArch64 / RISC-V64), emit the canonical
     * opcode-prefix bytes that HotSpot recognises (see {@code AMD64TestAssembler}
     * in the OpenJDK JTreg sources), and advance the buffer position past the
     * barrier.
     *
     * @return offset within {@code buf} where the barrier begins —
     *         caller registers {@code new Mark(returnedOffset, ENTRY_BARRIER_PATCH)}.
     */
    abstract int emitNMethodBarrier(ByteBuffer buf);

    protected static int baseOffset(Class<?> type, Annotation[] annotations) {
        if (type.isArray() && type.getComponentType().isPrimitive()) {
            return arrayBaseOffset(type);
        }

        for (Annotation annotation : annotations) {
            if (annotation instanceof FieldOffset) {
                return fieldOffset(type, ((FieldOffset) annotation).value());
            }
        }

        throw new IllegalArgumentException("Unsupported argument type: " + type);
    }

    protected static int arrayBaseOffset(Class<?> arrayType) {
        MetaAccessProvider meta = JVMCI.getRuntime().getHostJVMCIBackend().getMetaAccess();
        JavaKind elementKind = JavaKind.fromJavaClass(arrayType.getComponentType());
        return meta.getArrayBaseOffset(elementKind);
    }

    protected static int fieldOffset(Class<?> type, String fieldName) {
        MetaAccessProvider meta = JVMCI.getRuntime().getHostJVMCIBackend().getMetaAccess();
        ResolvedJavaField[] fields = meta.lookupJavaType(type).getInstanceFields(true);
        if (fields == null || fields.length == 0) {
            throw new IllegalArgumentException(type.getName() + " does not have instance fields");
        }

        if (fieldName.isEmpty()) {
            return fields[0].getOffset();
        }

        for (ResolvedJavaField field : fields) {
            if (field.getName().equals(fieldName)) {
                return field.getOffset();
            }
        }
        throw new IllegalArgumentException("No such field: " + type.getName() + "." + fieldName);
    }

    /**
     * Emit an AMD64 LEA whose supplied encoding uses a signed disp8 ModRM.
     * Object/array offsets above 127 must use disp32: encoding 128..255 in a
     * disp8 silently addresses before the object because x86 sign-extends it.
     */
    protected static void emitLea(ByteBuffer buf, int disp8Code, int displacement) {
        if (displacement < 0) {
            throw new IllegalArgumentException("Negative object offset: " + displacement);
        }
        if (displacement <= Byte.MAX_VALUE) {
            emit(buf, disp8Code);
            buf.put((byte) displacement);
        } else {
            // Change ModRM.mod from 01 (disp8) to 10 (disp32). All supplied
            // LEA templates have their ModRM byte in the low byte of the code.
            emit(buf, disp8Code + 0x40);
            buf.putInt(displacement);
        }
    }

    protected static void emit(ByteBuffer buf, int code) {
        if ((code >>> 24) != 0) buf.put((byte) (code >>> 24));
        if ((code >>> 16) != 0) buf.put((byte) (code >>> 16));
        if ((code >>> 8) != 0) buf.put((byte) (code >>> 8));
        if (code != 0) buf.put((byte) code);
    }
}
