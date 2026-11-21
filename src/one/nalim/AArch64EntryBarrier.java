package one.nalim;

import jdk.vm.ci.code.site.Call;
import jdk.vm.ci.code.site.DataPatch;
import jdk.vm.ci.code.site.DataSectionReference;
import jdk.vm.ci.code.site.Mark;
import jdk.vm.ci.code.site.Site;
import jdk.vm.ci.hotspot.HotSpotVMConfigAccess;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** HotSpot's AArch64 BarrierSetAssembler::nmethod_entry_barrier, with a frameless hot body. */
final class AArch64EntryBarrier {
    static final int FRAME_SIZE = 16;       // saved x29, caller's LR
    static final int GUARD_SIZE = Integer.BYTES;
    // Largest guard (44), long conditional jump (24), body alignment (3),
    // restore and long jump (32), PAC/frame/call/return (40).
    static final int MAX_BYTES = 44 + 24 + 3 + 32 + 40;
    private static final int GUARD_PC = 4; // after the independently patchable verified-entry NOP
    private static final String DATA = "CompilerToVM::Data::";
    private static final String TYPE = "NMethodPatchingType::";

    private final byte[] guard; // immutable instruction prefix; copied into each nmethod
    private final boolean pac;
    private final long stub;
    private final int patchMark;
    private final int frameMark;

    AArch64EntryBarrier(HotSpotVMConfigAccess config, int patchMark, int frameMark) {
        this.patchMark = patchMark;
        this.frameMark = frameMark;
        pac = config.getFieldValue("VM_Version::_rop_protection", Boolean.class, "bool");
        stub = config.getFieldValue(DATA + "nmethod_entry_barrier", Long.class, "address");
        if (stub == 0) throw new IllegalStateException("HotSpot has no AArch64 nmethod entry-barrier stub");
        int disarmed = config.getFieldValue(DATA + "thread_disarmed_guard_value_offset", Integer.class, "int");
        int type = config.getFieldValue(DATA + "BarrierSetAssembler_nmethod_patching_type", Integer.class, "int");
        boolean concurrentCode = type == config.getConstant(TYPE + "conc_instruction_and_data_patch", Integer.class);
        boolean concurrentData = type == config.getConstant(TYPE + "conc_data_patch", Integer.class, -1);
        if (!concurrentCode && !concurrentData && type != config.getConstant(TYPE + "stw_instruction_and_data_patch", Integer.class)) {
            throw new IllegalStateException("Unknown AArch64 nmethod patching type: " + type);
        }
        long epoch = concurrentCode ? config.getFieldValue(DATA + "BarrierSetAssembler_patching_epoch_addr", Long.class, "address", 0L) : 0;
        ByteBuffer buf = ByteBuffer.allocate(44).order(ByteOrder.nativeOrder());
        buf.putInt(0xd503201f);                        // nop (NativeJump::patch_verified_entry)
        buf.putInt(0x18000008);                        // ldr w8, guard; relocated by JVMCI
        if (epoch != 0) {
            // Keep the guard -> global epoch address dependency from HotSpot.
            // A DWORD load zeroed x8[63:32], so the ORR leaves x9 unchanged.
            loadAddress(buf, 9, epoch);
            buf.putInt(0xaa488129);                    // orr x9, x9, x8, lsr #32
            buf.putInt(0xb9400129);                    // ldr w9, [x9]
            buf.putInt(0xaa098108);                    // orr x8, x8, x9, lsl #32
            loadThreadGuard(buf, disarmed, true);      // {guard, epoch} in one load
            buf.putInt(0xeb09011f);                    // cmp x8, x9
        } else {
            if (concurrentData || concurrentCode) buf.putInt(0xd50339bf); // dmb ishld (acquire guard)
            // JDK 21 JVMCI doesn't export the epoch address. Pay the cross-modify
            // fence here, rather than relying on a thread epoch we cannot compare.
            if (concurrentCode) buf.putInt(0xd5033fdf); // isb
            loadThreadGuard(buf, disarmed, false);
            buf.putInt(0x6b09011f);                    // cmp w8, w9
        }
        guard = Arrays.copyOf(buf.array(), buf.position());
    }

    private static void loadThreadGuard(ByteBuffer buf, int offset, boolean pair) {
        int scale = pair ? 8 : 4;
        if (offset < 0 || offset % scale != 0 || offset / scale > 0xfff) {
            throw new IllegalStateException("Unencodable AArch64 thread guard offset: " + offset);
        }
        buf.putInt((pair ? 0xf9400389 : 0xb9400389) | (offset / scale << 10)); // ldr x9/w9, [x28, #offset]
    }

    private static void loadAddress(ByteBuffer buf, int reg, long address) {
        buf.putInt(0xd2800000 | ((int) address & 0xffff) << 5 | reg); // movz
        for (int half = 1; half < 4; half++) {
            int value = (int) (address >>> (16 * half)) & 0xffff;
            if (value != 0) buf.putInt(0xf2800000 | half << 21 | value << 5 | reg); // movk
        }
    }

    // Most methods (including all adapters) fit the 19-bit conditional branch.
    // Raw @Code may include arbitrarily large literal pools. Reserve a far branch
    // in that case; the distance is filled in before JVMCI sees the buffer.
    private static boolean far(ByteBuffer buf) {
        return buf.capacity() >= (1 << 20);
    }

    void emitEntry(ByteBuffer buf) {
        if (buf.position() != 0) throw new IllegalStateException("AArch64 entry barrier must start at offset zero");
        buf.put(guard);
        buf.position(buf.position() + (far(buf) ? 24 : 4));
    }

    // PC-relative over the full signed int range; never depends on where JVMCI installs the method.
    private static void jump(ByteBuffer buf, int target, boolean far) {
        int disp = target - buf.position();
        if (!far) {
            buf.putInt(0x14000000 | ((disp >> 2) & 0x03ffffff)); // b target
        } else {
            buf.putInt(0x10000008);                              // adr x8, .
            buf.putInt(0x52800009 | (disp & 0xffff) << 5);      // movz w9, #lo
            buf.putInt(0x72a00009 | (disp >>> 16) << 5);        // movk w9, #hi, lsl #16
            buf.putInt(0x8b29c108);                              // add x8, x8, w9, sxtw
            buf.putInt(0xd61f0100);                              // br x8
        }
    }

    Site[] finish(ByteBuffer buf, Call directJump) {
        boolean far = far(buf);
        int hot = guard.length + (far ? 24 : 4);
        // A raw body may end with literal data rather than an instruction.
        while ((buf.position() & 3) != 0) buf.put((byte) 0);
        int restore = buf.position();
        buf.putInt(0xa8c17bfd);                                  // ldp x29, x30, [sp], #16
        if (pac) {
            buf.putInt(0xd503239f);                              // autiaz
            buf.putInt(0xf94003df);                              // ldr xzr, [x30] (validate authenticated LR)
        }
        jump(buf, hot, far);

        int slow = buf.position();
        if (pac) {
            buf.putInt(0xf94003df);                              // ldr xzr, [x30]
            buf.putInt(0xd503231f);                              // paciaz (HotSpot signs LR with zero modifier)
        }
        buf.putInt(0xa9bf7bfd);                                  // stp x29, x30, [sp, #-16]!
        buf.putInt(0x910003fd);                                  // mov x29, sp
        int frameComplete = buf.position();
        // The stub anchors its last Java frame at our SP/FP and BLR return PC.
        // Its rejected-entry path unwinds this frame instead of returning here.
        // Use the permanent VM stub address (as in JVMCI's AArch64TestAssembler),
        // not a BL that might be out of range in a large code cache.
        loadAddress(buf, 8, stub);
        buf.putInt(0xd63f0100);                                  // blr x8
        jump(buf, restore, false);                               // call return PC is still frame-complete

        int end = buf.position();
        buf.position(guard.length);
        if (far) {
            buf.putInt(0x540000c0);                              // b.eq hot (24 bytes)
            jump(buf, slow, true);
        } else {
            buf.putInt(0x54000001 | ((slow - guard.length) >> 2) << 5); // b.ne slow
        }
        buf.position(end);

        DataSectionReference ref = new DataSectionReference();
        ref.setOffset(0);
        // AArch64 NativeNMethodBarrier finds its writable int via the section_word
        // relocation at precisely ENTRY_BARRIER_PATCH. A mark alone is not a relocation.
        DataPatch patch = new DataPatch(GUARD_PC, ref);
        Mark entry = new Mark(GUARD_PC, patchMark);
        Mark frame = new Mark(frameComplete, frameMark);
        return directJump == null ? new Site[] {entry, patch, frame} : new Site[] {entry, patch, directJump, frame};
    }
}
