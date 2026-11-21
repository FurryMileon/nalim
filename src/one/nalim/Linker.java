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

import jdk.vm.ci.code.CodeCacheProvider;
import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.code.site.Call;
import jdk.vm.ci.code.site.DataPatch;
import jdk.vm.ci.code.site.Mark;
import jdk.vm.ci.code.site.Site;
import jdk.vm.ci.hotspot.HotSpotCompiledCode;
import jdk.vm.ci.hotspot.HotSpotCompiledNmethod;
import jdk.vm.ci.hotspot.HotSpotForeignCallTarget;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime;
import jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod;
import jdk.vm.ci.hotspot.HotSpotVMConfigAccess;
import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.runtime.JVMCI;
import jdk.vm.ci.runtime.JVMCIBackend;
import jdk.vm.ci.runtime.JVMCICompiler;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Links native methods to the provided machine code using JVMCI.
 */
public class Linker {
    // Open java.base/jdk.internal.misc and jdk.internal.vm.ci/jdk.vm.ci.*
    // before the JVM resolves the JVMCI types below. This replaces the old
    // --add-exports list and makes -javaagent optional. The JVMCI module must
    // still be resolved at startup (--add-modules on JDK 25+; JDK 21-24 also
    // require UnlockExperimentalVMOptions + EnableJVMCI).
    static {
        ModuleOpener.openJvmci();
    }

    private static final JVMCIBackend jvmci = JVMCI.getRuntime().getHostJVMCIBackend();
    private static final CodeCacheProvider codeCache = jvmci.getCodeCache();
    private static final ConcurrentHashMap<String, Boolean> nativeLibraries = new ConcurrentHashMap<>();
    private static final CallingConvention callingConvention = CallingConvention.getInstance();
    private static final String ARCH = System.getProperty("os.arch", "");
    private static final boolean AMD64 = ARCH.equalsIgnoreCase("amd64") || ARCH.equalsIgnoreCase("x86_64");
    private static final boolean SYSV_AMD64 = AMD64 && "Linux".equals(System.getProperty("os.name"));

    // JDK 21+ requires every installed nmethod to carry an entry barrier
    // (Loom / JEP 444 made BarrierSetNMethod mandatory for every GC). The
    // values are queried from HotSpotVMConfigAccess so we track any future
    // reshuffling of the enum in jvmciCodeInstaller.hpp.
    // On JDK ≤ 20 the constants are absent — lookupMarkId returns null and
    // we install with the legacy empty-Site[] form.
    private static final Integer MARK_FRAME_COMPLETE = lookupMarkId("CodeInstaller::FRAME_COMPLETE");
    private static final Integer MARK_ENTRY_BARRIER_PATCH = lookupMarkId("CodeInstaller::ENTRY_BARRIER_PATCH");
    private static final Integer THREAD_DISARMED_OFFSET = lookupConfigField(
            "CompilerToVM::Data::thread_disarmed_guard_value_offset", Integer.class, "int");
    private static final Long NMETHOD_ENTRY_BARRIER = lookupConfigField(
            "CompilerToVM::Data::nmethod_entry_barrier", Long.class, "address");

    // Alignment of installed nmethod entries; limits how strongly a literal in
    // the inline pool can be aligned. 32 by default on AMD64, but the flag is
    // user-settable, so read the live value rather than assuming.
    private static final int CODE_ENTRY_ALIGNMENT = lookupCodeEntryAlignment();

    private static int lookupCodeEntryAlignment() {
        try {
            HotSpotVMConfigAccess cfg = new HotSpotVMConfigAccess(HotSpotJVMCIRuntime.runtime().getConfigStore());
            return cfg.getFlag("CodeEntryAlignment", Integer.class);
        } catch (Throwable t) {
            return 16;   // conservative pre-existing assumption
        }
    }

    private static Integer lookupMarkId(String name) {
        try {
            HotSpotVMConfigAccess cfg = new HotSpotVMConfigAccess(HotSpotJVMCIRuntime.runtime().getConfigStore());
            return cfg.getConstant(name, Integer.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private static <T> T lookupConfigField(String name, Class<T> type, String cppType) {
        try {
            HotSpotVMConfigAccess cfg = new HotSpotVMConfigAccess(HotSpotJVMCIRuntime.runtime().getConfigStore());
            return cfg.getFieldValue(name, type, cppType);
        } catch (Throwable t) {
            return null;
        }
    }

    static {
        loadLibrary("java");
    }

    public static synchronized void loadLibrary(String name) {
        // Publish a library as loaded only while holding this monitor. Without
        // serialization, a second linker thread can observe the map entry and
        // call findAddress before the first thread's System.load has finished.
        if (nativeLibraries.putIfAbsent(name, Boolean.TRUE) == null) {
            try {
                if (name.indexOf('/') >= 0 || name.indexOf('\\') > 0) {
                    System.load(name);
                } else {
                    System.loadLibrary(name);
                }
                NativeMappings.invalidate();
            } catch (RuntimeException | Error t) {
                // A failed first attempt must not poison the deduplication map;
                // callers may fix java.library.path / extract the binary and retry.
                nativeLibraries.remove(name, Boolean.TRUE);
                throw t;
            }
        }
    }

    public static long findAddress(String symbol) {
        return NativeSymbols.find(symbol);
    }

    /**
     * Find an exact Java equivalent for a pure integer function in a loaded
     * Linux AMD64 library. A static-final handle can inline into the Java
     * caller, unlike an installed native nmethod. The type must describe the
     * real C function. Missing symbols, unrecognized bodies, or failure to
     * read the non-writable executable text via procfs produce an empty result.
     * This does not install or rebind a Java method. The returned operation
     * represents the inspected code; the library must keep that text stable.
     */
    public static Optional<MethodHandle> findIntrinsic(String symbol, MethodType type) {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(type, "type");
        if (!SYSV_AMD64 || NO_INLINE) return Optional.empty();
        Class<?> result = type.returnType();
        int n = type.parameterCount();
        if ((result != int.class && result != long.class) || (n != 1 && n != 2)) return Optional.empty();
        for (int i = 0; i < n; i++) if (type.parameterType(i) != result) return Optional.empty();
        long address = findAddress(symbol);
        if (address == 0) return Optional.empty();
        byte[] code = NativeMappings.readExecutablePrefix(address, AMD64ScalarLeaf.MAX_PREFIX_BYTES);
        if (code == null) return Optional.empty();
        AMD64ScalarLeaf leaf = AMD64ScalarLeaf.matchWord(code, code.length, result, n);
        return leaf == null ? Optional.empty() : Optional.of(leaf.handle());
    }

    private static final int SMALL_ADAPTER_CAPACITY = 128;

    public static void linkClass(Class<?> c) {
        Library library = c.getAnnotation(Library.class);
        if (library != null) {
            loadLibrary(library.value());
        }

        Method[] methods = c.getDeclaredMethods();
        boolean reuseAdapters = false;
        if (SYSV_AMD64) {
            int nativeMethods = 0;
            for (Method m : methods) {
                if (Modifier.isNative(m.getModifiers()) && ++nativeMethods == 2) {
                    reuseAdapters = true;
                    break;
                }
            }
        }
        InlineScratch scratch = null;
        ByteBuffer reusable = null;
        for (Method m : methods) {
            Code code = m.getAnnotation(Code.class);
            if (code != null) {
                installHexCode(m, code.value());
            } else {
                Link link = m.getAnnotation(Link.class);
                if (link != null) {
                    if (reuseAdapters && reusable == null) {
                        reusable = ByteBuffer.allocate(SMALL_ADAPTER_CAPACITY).order(ByteOrder.nativeOrder());
                    }
                    scratch = linkNative(m, link.name(), link.naked(), scratch, reusable);
                }
            }
        }
    }

    public static void linkMethod(Method m) {
        Code code = m.getAnnotation(Code.class);
        if (code != null) {
            installHexCode(m, code.value());
            return;
        }

        Link link = m.getAnnotation(Link.class);
        if (link != null) {
            linkMethod(m, link.name(), link.naked());
        } else {
            linkMethod(m, m.getName(), false);
        }
    }

    public static void linkMethod(Method m, String symbol, boolean naked) {
        linkNative(m, symbol, naked, null, null);
    }

    // Return the workspace for the next method in this class. A framed or
    // no-inline binding never allocates one just to pass it on.
    private static InlineScratch linkNative(Method m, String symbol, boolean naked, InlineScratch scratch,
                                            ByteBuffer reusable) {
        checkMethodType(m);

        Library library = m.getAnnotation(Library.class);
        if (library != null) {
            loadLibrary(library.value());
        }

        if (symbol.isEmpty()) {
            symbol = m.getName();
        }
        long address = findAddress(symbol);
        if (address == 0) {
            throw new IllegalArgumentException("Symbol not found: " + symbol);
        }

        Class<?>[] types = m.getParameterTypes();
        boolean framed = !naked && callingConvention.requiresFramedCall(types);
        Class<?> returnType = m.getReturnType();
        boolean pendingScalarShuffle = SYSV_AMD64 && !naked && !framed && !NO_INLINE
                && AMD64ScalarLeaf.accepts(returnType, types);
        // 128 bytes covers the entry barrier, six disp32 LEAs and the largest
        // frameless fallback. Reserve Win64 stack marshalling separately: up to
        // 8 bytes to load a spill, 7 for a field LEA, 8 for the outgoing store.
        ByteBuffer buf = reusable == null
                ? ByteBuffer.allocate(framed ? 160 + 24 * Math.max(0, types.length - 4) : SMALL_ADAPTER_CAPACITY)
                            .order(ByteOrder.nativeOrder())
                : reusable.clear();
        emitEntryBarrierPrologue(buf);
        int shuffleStart = buf.position();

        // Scalar templates already consume Java registers. Don't generate an
        // unused shuffle for them. A miss restores the regular shuffle before
        // either the general inliner or the fallback sees the buffer.
        Annotation[][] annotations = null;
        if (!naked && !pendingScalarShuffle) {
            // All calling conventions consult annotations only for reference arguments.
            for (Class<?> type : types) {
                if (!type.isPrimitive()) {
                    annotations = m.getParameterAnnotations();
                    break;
                }
            }
            if (!framed) callingConvention.javaToNative(buf, types, annotations);
        }

        // Only the copied body may use a resolved entry thunk. A rejected leaf
        // must still call the original symbol with its original calling convention.
        Call directJump = null;
        if (framed) {
            // Framed adapter (Win64): the callee owns shadow space and stack
            // args live in the caller frame, so tail jumps and auto-inline of
            // foreign-ABI bodies are unsafe. Return normalization is folded
            // into the frame epilogue.
            directJump = tryEmitDirectFramedCall(buf, address, returnType, types, annotations);
            if (directJump == null) {
                callingConvention.emitFramedCall(buf, address, returnType, types, annotations);
            }
        } else {
            if (AMD64 && !NO_INLINE) {
                if (scratch == null) scratch = new InlineScratch();
                ByteBuffer inline = tryInlineSmallFunction(buf, address, symbol, returnType, scratch,
                        !naked && SYSV_AMD64 ? shuffleStart : -1, types, pendingScalarShuffle);
                if (inline != null) {
                    installCode(m, inline, null, scratch.rebases, inline == reusable);
                    return scratch;
                }
            }
            if (CallingConvention.needsReturnNormalization(returnType)) {
                directJump = tryEmitDirectNarrowCall(buf, address, returnType, types);
                if (directJump == null) {
                    callingConvention.emitCallAndNormalize(buf, address, returnType, types);
                }
            } else {
                directJump = tryEmitDirectJump(buf, address);
                if (directJump == null) {
                    callingConvention.emitCall(buf, address);
                }
            }
        }

        installCode(m, buf, directJump, null, buf == reusable);
        return scratch;
    }

    // The old AMD64 fallback is `movabs rax, address; jmp rax` (12 bytes and
    // an indirect branch). When every possible nmethod allocation is within a
    // signed rel32 of the target, let JVMCI relocate a five-byte `jmp rel32`.
    // CodeCacheProvider performs the ASLR-aware reachability calculation and
    // CodeInstaller patches the displacement only after placing the nmethod.
    private static final boolean NO_DIRECT_JUMP = Boolean.getBoolean("nalim.nodirect");
    private static volatile int directJumpMethods;

    public static int getDirectJumpMethodCount() {
        return directJumpMethods;
    }

    private static Call tryEmitDirectJump(ByteBuffer buf, long address) {
        if (NO_DIRECT_JUMP) {
            return null;
        }
        if (!AMD64) {
            return null;
        }

        long maxOffset = codeCache.getMaxCallTargetOffset(address);
        if (maxOffset < 0 || maxOffset > Integer.MAX_VALUE) {
            return null;
        }

        int pcOffset = buf.position();
        buf.put((byte) 0xE9).putInt(0);  // JVMCI patches disp32 at install time
        directJumpMethods++;
        return new Call(new HotSpotForeignCallTarget(address), pcOffset, 5, true, null);
    }

    // Framed-call variant of the direct-call optimization: same reachability
    // gate, but the frame prologue/epilogue wrap the JVMCI-bound call rel32.
    private static Call tryEmitDirectFramedCall(ByteBuffer buf, long address, Class<?> returnType,
                                                Class<?>[] types, Annotation[][] annotations) {
        if (NO_DIRECT_JUMP || !AMD64) {
            return null;
        }
        long maxOffset = codeCache.getMaxCallTargetOffset(address);
        if (maxOffset < 0 || maxOffset > Integer.MAX_VALUE) {
            return null;
        }
        int pcOffset = callingConvention.emitDirectFramedCall(
                buf, returnType, types, annotations);
        if (pcOffset < 0) {
            return null;
        }
        directJumpMethods++;
        return new Call(new HotSpotForeignCallTarget(address), pcOffset, 5, true, null);
    }

    // Narrow-return bridge with a JVMCI-relocated `call rel32` instead of
    // `movabs rax; call rax`: 5 bytes vs 12 and no indirect-branch predictor
    // entry. Same reachability gate as the direct jump.
    private static Call tryEmitDirectNarrowCall(
            ByteBuffer buf, long address, Class<?> returnType, Class<?>[] parameterTypes) {
        if (NO_DIRECT_JUMP || !AMD64) {
            return null;
        }
        long maxOffset = codeCache.getMaxCallTargetOffset(address);
        if (maxOffset < 0 || maxOffset > Integer.MAX_VALUE) {
            return null;
        }
        int pcOffset = callingConvention.emitDirectCallAndNormalize(buf, returnType, parameterTypes);
        if (pcOffset < 0) {
            return null;
        }
        directJumpMethods++;
        return new Call(new HotSpotForeignCallTarget(address), pcOffset, 5, true, null);
    }

    /**
     * Try to copy the body of the function at {@code address} into {@code buf}
     * (auto-inline). Succeeds when every reachable path is made of whitelisted
     * position-independent instructions ending in {@code ret} / {@code rep ret}.
     * Accepted control flow: conditional branches in either direction
     * (including loop backedges) and unconditional {@code jmp rel8/rel32},
     * as long as every target is a verified instruction boundary inside the
     * window; calls and indirect jumps remain on the fallback path.
     * RIP-relative <em>loads</em> are accepted when the source
     * lies in a read-only mapping (compiler constant literals): the literal is
     * copied into a pool emitted after the body and the disp32 is re-pointed at
     * it — RIP-relative addressing keeps working because pool and code live in
     * the same nmethod. Any call, indirect branch, store-to-RIP,
     * EVEX or unknown encoding → return false and the caller emits the
     * tail-call stub instead.
     */
    private static final int MAX_INLINE_BYTES = 128;
    private static final int MAX_RELOCATIONS = 8;
    private static final boolean TRACE_INLINE = Boolean.getBoolean("nalim.trace.inline");
    // Safety valve: -Dnalim.noinline=true forces the tail-call stub for every
    // method (auto-inline off) without code changes. Useful to bisect issues.
    private static final boolean NO_INLINE = Boolean.getBoolean("nalim.noinline");
    // Safety valve for the rebase relocation only (RIP operands re-aimed at
    // their original target after install): -Dnalim.norebase=true falls back
    // to the tail-call stub for bodies that would need it.
    private static final boolean NO_REBASE = Boolean.getBoolean("nalim.norebase");

    // Diagnostics only (linking is effectively single-threaded).
    private static volatile int inlinedMethods;

    public static int getInlinedMethodCount() {
        return inlinedMethods;
    }

    // Owned by one linkClass invocation, never by a thread-local or a global cache.
    // Entries in lengths: 0 = unseen, QUEUED = block start, INTERIOR = not a target,
    // positive = verified instruction length. Only symbols with RIP operands need fixups.
    private static final byte QUEUED = -2, INTERIOR = -1;
    private static final class InlineScratch {
        final byte[] code = new byte[MAX_INLINE_BYTES];
        final byte[] lengths = new byte[MAX_INLINE_BYTES];
        final byte[] queue = new byte[MAX_INLINE_BYTES];
        final Insn insn = new Insn();
        Fixups literals;
        Fixups rebases;
    }

    private static final class Fixups {
        final long[] target = new long[MAX_RELOCATIONS];
        final int[] disp = new int[MAX_RELOCATIONS];
        final int[] next = new int[MAX_RELOCATIONS];
        final int[] size = new int[MAX_RELOCATIONS];
        int count;

        void add(long address, int field, int after, int width) {
            target[count] = address;
            disp[count] = field;
            next[count] = after;
            size[count++] = width;
        }

        void place(int start, int[] offsets) {
            for (int r = 0; r < count; r++) {
                disp[r] = start + (offsets == null ? disp[r] : offsets[disp[r]]);
                next[r] = start + (offsets == null ? next[r] : offsets[next[r]]);
            }
        }
    }

    private static ByteBuffer tryInlineSmallFunction(
            ByteBuffer buf, long address, String symbol, Class<?> returnType,
            InlineScratch scratch, int shuffleStart, Class<?>[] types, boolean pendingScalarShuffle) {
        address = NativeMappings.copyEntry(address, scratch.code);
        if (address == 0) {
            if (pendingScalarShuffle) callingConvention.javaToNative(buf, types, null);
            return null; // caller still has the original symbol for the native fallback
        }
        int window = NativeMappings.pageWindow(address, MAX_INLINE_BYTES);
        if (shuffleStart >= 0) {
            AMD64ScalarLeaf leaf = null;
            if (pendingScalarShuffle) {
                leaf = AMD64ScalarLeaf.matchWord(scratch.code, window, returnType, types.length);
                if (leaf != null) leaf.emitJavaBody(buf);
                else callingConvention.javaToNative(buf, types, null); // exact primitive signature
            }
            if (leaf != null || AMD64MemoryLeaf.tryEmit(buf, shuffleStart, scratch.code, window, returnType, types)) {
                // Unlike the general CFG path, this body has no relocations.
                if (scratch.rebases != null) scratch.rebases.count = 0;
                inlinedMethods++;
                if (TRACE_INLINE) System.out.printf("[nalim] %s: inlined %dB Java-ABI leaf%n", symbol, buf.position() - shuffleStart);
                return buf;
            }
        }
        boolean splitEligible = shuffleStart >= 0 && hasSeveralIntegerArgs(types);
        ByteBuffer inline = tryInlineWindow(buf, address, symbol, returnType, scratch, window, splitEligible);
        if (inline != null || window == MAX_INLINE_BYTES) return inline;

        int expanded = NativeMappings.extendCodeWindow(address, scratch.code, window);
        if (expanded <= window) return null;
        return tryInlineWindow(buf, address, symbol, returnType, scratch, expanded, splitEligible);
    }

    private static boolean hasSeveralIntegerArgs(Class<?>[] types) {
        int count = 0;
        for (Class<?> type : types) {
            if (type != float.class && type != double.class && ++count == 4) return true;
        }
        return false;
    }

    private static ByteBuffer tryInlineWindow(ByteBuffer buf, long address, String symbol,
                                              Class<?> returnType, InlineScratch scratch, int window,
                                              boolean splitEligible) {
        byte[] code = scratch.code;

        // ── pass 1: decode and validate ─────────────────────────────────
        Insn insn = scratch.insn;
        Fixups literals = scratch.literals, rebases = scratch.rebases;
        if (literals != null) literals.count = 0;
        if (rebases != null) rebases.count = 0;
        int end = 0;
        int instructions = 0;
        boolean hasBranch = false;
        int retCount = 0;
        int terminalRetOffset = -1;
        int terminalRetLength = 0;

        // Reachability walk over a verified CFG: conditional branches in either
        // direction (including loop backedges), unconditional forward/backward
        // jumps, ordinary whitelisted instructions and RET. Every branch target
        // must land on a verified instruction boundary inside the window — a
        // branch into the middle of an instruction is rejected either here (the
        // target is already owned by another offset) or when the target block
        // decodes and collides with owned bytes.
        byte[] lengths = scratch.lengths, queue = scratch.queue;
        Arrays.fill(lengths, 0, window, (byte) 0);
        int head = 0;
        int tail = 0;
        queue[tail++] = 0;
        lengths[0] = QUEUED;

        while (head < tail) {
            int i = queue[head++] & 0xff;
            for (;;) {
                if (i >= window) return null;
                if (lengths[i] > 0) break;            // merges into a verified block
                if (lengths[i] == INTERIOR) return null;

                int b = code[i] & 0xFF;
                int length;
                boolean terminator = false;
                boolean branch = false;
                boolean hasFallthrough = false;
                int target = -1;

                if (b == 0xC3) {
                    length = 1;
                    retCount++;
                    terminalRetOffset = i;
                    terminalRetLength = length;
                    terminator = true;
                } else if (b == 0xF3 && i + 1 < window && (code[i + 1] & 0xFF) == 0xC3) {
                    length = 2;
                    retCount++;
                    terminalRetOffset = i;
                    terminalRetLength = length;
                    terminator = true;
                } else if (b >= 0x70 && b <= 0x7F && i + 2 <= window) {
                    length = 2;
                    target = i + length + (byte) code[i + 1];
                    terminator = true;
                    branch = true;
                    hasFallthrough = true;
                } else if (b == 0x0F && i + 6 <= window
                        && (code[i + 1] & 0xFF) >= 0x80 && (code[i + 1] & 0xFF) <= 0x8F) {
                    length = 6;
                    int disp = (code[i + 2] & 0xFF)
                            | (code[i + 3] & 0xFF) << 8
                            | (code[i + 4] & 0xFF) << 16
                            | (code[i + 5] & 0xFF) << 24;
                    target = i + length + disp;
                    terminator = true;
                    branch = true;
                    hasFallthrough = true;
                } else if (b == 0xEB && i + 2 <= window) {
                    // jmp rel8 — no fallthrough
                    length = 2;
                    target = i + length + (byte) code[i + 1];
                    terminator = true;
                    branch = true;
                } else if (b == 0xE9 && i + 5 <= window) {
                    // jmp rel32 — no fallthrough
                    length = 5;
                    int disp = (code[i + 1] & 0xFF)
                            | (code[i + 2] & 0xFF) << 8
                            | (code[i + 3] & 0xFF) << 16
                            | (code[i + 4] & 0xFF) << 24;
                    target = i + length + disp;
                    terminator = true;
                    branch = true;
                } else {
                    if (!decode(code, i, window, insn)) {
                        if (TRACE_INLINE) {
                            System.out.printf("[nalim] %s: inline bail at +0x%x (opcode 0x%02x)%n", symbol, i, b);
                        }
                        return null;
                    }
                    length = insn.length;
                }

                if (length > 15 || i + length > window) return null;
                for (int k = i + 1; k < i + length; k++) {
                    if (lengths[k] != 0) return null; // overlap or queued branch into this instruction
                    lengths[k] = INTERIOR;
                }
                lengths[i] = (byte) length;
                end = Math.max(end, i + length);
                instructions++;

                if (!terminator && insn.ripDispOff >= 0 && !isRipNop(code, i)) {
                    int dispPos = i + insn.ripDispOff;
                    int disp = (code[dispPos] & 0xFF)
                            | (code[dispPos + 1] & 0xFF) << 8
                            | (code[dispPos + 2] & 0xFF) << 16
                            | (code[dispPos + 3] & 0xFF) << 24;
                    long operand = address + i + insn.length + disp;

                    // Preferred: copy a small read-only literal into a pool
                    // inside the nmethod (cache-local, placement-independent).
                    // Requires alignment no stricter than the installed entry.
                    if (insn.ripLoadSize > 0
                            && (literals == null || literals.count < MAX_RELOCATIONS)
                            && Math.min(insn.ripLoadSize, 32) <= CODE_ENTRY_ALIGNMENT
                            && NativeMappings.isReadOnlyRange(operand, insn.ripLoadSize)) {
                        if (literals == null) scratch.literals = literals = new Fixups();
                        literals.add(operand, dispPos, i + length, insn.ripLoadSize);
                    } else {
                        // Rebase: keep the instruction aimed at its ORIGINAL
                        // operand — the disp32 is rewritten after the nmethod
                        // is placed. Covers RIP-lea (mutable table bases) and
                        // loads whose literal is not poolable. Gate: the
                        // operand must be rel32-reachable from any possible
                        // nmethod placement (same test as direct jumps).
                        if (NO_REBASE || (rebases != null && rebases.count == MAX_RELOCATIONS)) {
                            if (TRACE_INLINE) {
                                System.out.printf("[nalim] %s: RIP operand at +0x%x needs rebase (%s)%n",
                                        symbol, i, NO_REBASE ? "disabled" : "slots exhausted");
                            }
                            return null;
                        }
                        long maxOffset = codeCache.getMaxCallTargetOffset(operand);
                        if (maxOffset < 0 || maxOffset > Integer.MAX_VALUE) {
                            if (TRACE_INLINE) {
                                System.out.printf("[nalim] %s: RIP operand at +0x%x beyond rel32 reach%n",
                                        symbol, i);
                            }
                            return null;
                        }
                        if (rebases == null) scratch.rebases = rebases = new Fixups();
                        rebases.add(operand, dispPos, i + length, 0);
                    }
                }

                if (branch) {
                    hasBranch = true;
                    int fallthrough = i + length;
                    for (int t = 0; t < (hasFallthrough ? 2 : 1); t++) {
                        int dest = (t == 0) ? target : fallthrough;
                        if (dest < 0 || dest >= window || lengths[dest] == INTERIOR) return null;
                        if (lengths[dest] == 0) {
                            lengths[dest] = QUEUED;
                            queue[tail++] = (byte) dest;
                        }
                    }
                    break;
                }
                if (terminator) break;
                i += length;
            }
        }
        if (retCount == 0) {
            if (TRACE_INLINE) {
                System.out.printf("[nalim] %s: no ret within %d-byte window%n", symbol, window);
            }
            return null;
        }

        boolean normalizeReturn = CallingConvention.needsReturnNormalization(returnType);
        // ── pass 2: copy body, emit literal pool, patch disp32 fields ───
        int preambleEnd = buf.position();
        // A separate target can help Zen dispatch a substantial body after
        // a shuffle of four or more integer arguments. Keep smaller shuffles and
        // tiny, branching, naked or narrow-return bodies on the fall-through path.
        boolean splitBody = splitEligible && !normalizeReturn && !hasBranch
                && instructions >= 8 && AMD64Dispatch.SPLIT_INLINE_BODY;
        int bodyStart = preambleEnd + (splitBody ? 2 : 0);
        int literalCount = literals == null ? 0 : literals.count;
        int capacity = bodyStart + end + (normalizeReturn ? AMD64NarrowBody.NORMALIZATION_SIZE * retCount : 0);
        for (int r = 0; r < literalCount; r++) {
            int size = literals.size[r], align = Math.min(size, 32);
            capacity = ((capacity + align - 1) & -align) + size;
        }
        capacity += 24; // finishEntryBarrier's slow path (also covers the smaller legacy trailer)
        if (capacity > buf.capacity()) {
            buf = ByteBuffer.allocate(capacity).order(buf.order()).put(buf.array(), 0, preambleEnd);
        }
        if (splitBody) buf.putShort((short) 0x00EB);             // jmp to the following instruction
        int[] offsets = null;
        if (normalizeReturn && (retCount != 1 || terminalRetOffset + terminalRetLength != end)) {
            offsets = AMD64NarrowBody.emit(buf, code, lengths, end, returnType);
            if (offsets == null) {
                if (TRACE_INLINE) System.out.printf("[nalim] %s: narrow exits exceed rel8 range%n", symbol);
                return null;
            }
        } else if (normalizeReturn) {
            // Keep every original offset before RET stable. Any forward Jcc
            // targeting the old RET now lands on the normalizer instead.
            buf.put(code, 0, terminalRetOffset);
            CallingConvention.emitAMD64ReturnNormalization(buf, returnType);
            buf.put((byte) 0xC3);
        } else {
            buf.put(code, 0, end);
        }
        if (literalCount != 0) literals.place(bodyStart, offsets);
        for (int r = 0; r < literalCount; r++) {
            // Preserve alignment required by MOVAPS/MOVAPD/MOVDQA (and their
            // 32-byte VEX forms). The installed entry is CodeEntryAlignment-
            // aligned (32 by default on AMD64), so a buffer offset aligned to
            // min(litSize, 32) stays aligned after the nmethod is placed;
            // larger requirements were rejected during the decode pass.
            int size = literals.size[r];
            int alignment = Math.min(size, 32);
            while ((buf.position() & (alignment - 1)) != 0) {
                buf.put((byte) 0);
            }
            int pool = buf.position();
            NativeMemory.copyToArray(literals.target[r], buf.array(), pool, size);
            buf.position(pool + size);
            // disp32 := pool − RIP, RIP = address of the next instruction.
            // Both live in the same nmethod, so the distance survives install.
            buf.putInt(literals.disp[r], pool - literals.next[r]);
        }
        int rebaseCount = rebases == null ? 0 : rebases.count;
        if (rebaseCount != 0) rebases.place(bodyStart, offsets);
        for (int r = 0; r < rebaseCount; r++) {
            // Placeholder 0 until the post-install patch: a stray execution in
            // the tiny install-to-patch window reads the nmethod's own bytes
            // instead of faulting on an unmapped address.
            buf.putInt(rebases.disp[r], 0);
        }

        inlinedMethods++;
        if (TRACE_INLINE) {
            System.out.printf(
                    "[nalim] %s: inlined %dB body, %d literal(s), %d rebase(s)%n",
                    symbol, buf.position() - bodyStart, literalCount, rebaseCount);
        }
        return buf;
    }

    /** Decoded form of a single whitelisted instruction. */
    private static final class Insn {
        int length;        // total encoded length in bytes
        int ripDispOff;    // offset of the RIP disp32 field within the instruction, or -1
        int ripLoadSize;   // literal bytes to copy into the pool (0 = operand never accessed)
    }

    /**
     * Decode one instruction at {@code code[off]}, accepting only leaf-safe,
     * position-independent x86-64 encodings: register/memory ALU and mov,
     * immediate ALU, shifts/rotates, mul/imul, movzx/movsx/movsxd, cmov/setcc,
     * push/pop, sign extends, inc/dec, bswap, shld/shrd, popcnt/lzcnt/tzcnt,
     * nop (including multi-prefix alignment padding), CET ENDBR64/ENDBR32
     * landing pads, mov-imm (incl. movabs r64, imm64), rep movs/stos,
     * scalar and packed SSE/SSE2/SSE4.1 math, and the VEX-encoded
     * AVX/AVX2/FMA/BMI subset handled by {@link #decodeVex}.
     * Memory operands must not use absolute addressing;
     * RIP-relative is reported via {@code ripDispOff}/{@code ripLoadSize} and
     * accepted only for pure loads, so the caller can relocate the literal.
     */
    private static boolean decode(byte[] code, int off, int limit, Insn out) {
        out.ripDispOff = -1;
        out.ripLoadSize = 0;
        int i = off;

        int b = u(code, i, limit);
        if (b < 0) return false;

        // In 64-bit mode C4/C5 are always VEX prefixes (LES/LDS don't exist).
        if (b == 0xC4 || b == 0xC5) {
            return decodeVex(code, off, limit, out);
        }

        // Alignment padding emitted inside function bodies (e.g. before loop
        // heads), PAUSE, and Intel CET ENDBR64/ENDBR32 landing pads.
        if (b == 0x66 || b == 0x2E || b == 0x3E || b == 0xF3) {
            int nopLen = paddingNopLength(code, off, limit);
            if (nopLen > 0) {
                out.length = nopLen;
                return true;
            }
        }

        // rep movs/stos — gcc/clang memcpy/memset idioms. Implicit rsi/rdi/rcx
        // operands are the function's own state; position-independent.
        if (b == 0xF3) {
            int b1 = u(code, i + 1, limit);
            int strOp = b1;
            int rexAdj = 0;
            if (b1 >= 0x48 && b1 <= 0x4F) {
                strOp = u(code, i + 2, limit);
                rexAdj = 1;
            }
            if (strOp == 0xA4 || strOp == 0xA5 || strOp == 0xAA || strOp == 0xAB) {
                out.length = 2 + rexAdj;
                return true;
            }
        }

        // One mandatory SSE prefix followed by an optional REX. 66 outside the
        // 0F map selects 16-bit operand size for a small accepted subset.
        int prefix = 0;
        if (b == 0x66 || b == 0xF2 || b == 0xF3) {
            prefix = b;
            b = u(code, ++i, limit);
            if (b < 0) return false;
        }

        // Optional REX prefix.
        int rex = 0;
        if ((b & 0xF0) == 0x40) {
            rex = b;
            b = u(code, ++i, limit);
            if (b < 0) return false;
        }
        boolean w = (rex & 8) != 0;

        int imm = 0;                 // trailing immediate bytes
        int loadSize = 0;            // RIP literal size when this op is a pure load
        boolean ripAllowed = false;
        boolean memAllowed = true;
        boolean regAllowed = true;
        boolean absOk = false;       // non-dereferencing ops may use no-base SIB
        int opLen;

        if (b == 0x0F) {
            int op = u(code, i + 1, limit);
            if (op < 0) return false;

            // Three-byte maps (SSSE3/SSE4.1): mandatory 66 prefix for the
            // accepted subset. Different tables, shared ModRM tail.
            if (op == 0x38 || op == 0x3A) {
                return decodeLegacy38or3A(code, off, limit, i, prefix, w, op, out);
            }

            opLen = 2;
            if (prefix == 0xF2 || prefix == 0xF3) {
                // Scalar SSE/SSE2: F3 = single (4B literals), F2 = double (8B).
                int sz = prefix == 0xF3 ? 4 : 8;
                switch (op) {
                    case 0x10:                                   // movss/movsd xmm, m
                        loadSize = sz; ripAllowed = true; break;
                    case 0x11:                                   // movss/movsd m, xmm — store
                        break;
                    case 0x12:                                   // movsldup (F3) / movddup (F2)
                        loadSize = prefix == 0xF3 ? 16 : 8; ripAllowed = true; break;
                    case 0x16:                                   // movshdup (F3 only)
                        if (prefix != 0xF3) return false;
                        loadSize = 16; ripAllowed = true; break;
                    case 0x2A:                                   // cvtsi2ss/sd xmm, r/m (int src)
                        loadSize = w ? 8 : 4; ripAllowed = true; break;
                    case 0x2C: case 0x2D:                        // cvt(t)ss/sd2si
                    case 0x51: case 0x58: case 0x59: case 0x5A:  // sqrt/add/mul/cvt
                    case 0x5C: case 0x5D: case 0x5E: case 0x5F:  // sub/min/div/max
                        loadSize = sz; ripAllowed = true; break;
                    case 0x5B:                                   // cvttps2dq (F3 only)
                        if (prefix != 0xF3) return false;
                        loadSize = 16; ripAllowed = true; break;
                    case 0x6F:                                   // movdqu xmm, m (F3 only)
                        if (prefix != 0xF3) return false;
                        loadSize = 16; ripAllowed = true; break;
                    case 0x70:                                   // pshufhw (F3) / pshuflw (F2)
                        imm = 1; loadSize = 16; ripAllowed = true; break;
                    case 0x7F:                                   // movdqu m, xmm — store (F3 only)
                        if (prefix != 0xF3) return false;
                        break;
                    case 0xB8: case 0xBC: case 0xBD:             // popcnt / tzcnt / lzcnt (F3 only)
                        if (prefix != 0xF3) return false;
                        loadSize = w ? 8 : 4; ripAllowed = true; break;
                    case 0xC2:                                   // cmpss/cmpsd /r, imm8
                        imm = 1; loadSize = sz; ripAllowed = true; break;
                    case 0x7E:                                   // movq xmm, m64 (F3 form only)
                        if (prefix != 0xF3) return false;
                        loadSize = 8; ripAllowed = true; break;
                    case 0xE6:                                   // cvtdq2pd (F3) / cvtpd2dq (F2)
                        loadSize = prefix == 0xF3 ? 8 : 16; ripAllowed = true; break;
                    default:
                        return false;
                }
            } else if (prefix == 0x66) {
                // Packed-double/integer SSE2. Loads of a full XMM operand are
                // relocatable 16-byte literals; store-direction encodings keep
                // non-RIP memory support but may never target the copied pool.
                switch (op) {
                    case 0x10: case 0x28: case 0x6F:              // movupd/movapd/movdqa load
                        loadSize = 16; ripAllowed = true; break;
                    case 0x11: case 0x29: case 0x7F:              // movupd/movapd/movdqa store
                        break;
                    case 0x14: case 0x15:                         // unpcklpd/unpckhpd
                    case 0x51:                                   // sqrtpd
                    case 0x54: case 0x55: case 0x56: case 0x57:  // packed bitwise
                    case 0x58: case 0x59: case 0x5A: case 0x5B:  // add/mul/convert
                    case 0x5C: case 0x5D: case 0x5E: case 0x5F:  // sub/min/div/max
                        loadSize = 16; ripAllowed = true; break;
                    case 0x2E: case 0x2F:                         // ucomisd/comisd
                        loadSize = 8; ripAllowed = true; break;
                    case 0x6E:                                   // movd/movq xmm, r/m
                        loadSize = w ? 8 : 4; ripAllowed = true; break;
                    case 0x7E: case 0xD6:                         // store direction: reg-reg only
                        memAllowed = false; break;
                    case 0xC2: case 0xC6: case 0x70:              // cmp/shuffle /r, imm8
                        imm = 1; loadSize = 16; ripAllowed = true; break;
                    // SSE2 integer core: pack/unpack, compares, arithmetic,
                    // logic and shifts — all load-direction (xmm, xmm/m128).
                    case 0x60: case 0x61: case 0x62: case 0x63:
                    case 0x64: case 0x65: case 0x66: case 0x67:
                    case 0x68: case 0x69: case 0x6A: case 0x6B:
                    case 0x6C: case 0x6D:
                    case 0x74: case 0x75: case 0x76:
                    case 0xD1: case 0xD2: case 0xD3: case 0xD4: case 0xD5:
                    case 0xD8: case 0xD9: case 0xDA: case 0xDB:
                    case 0xDC: case 0xDD: case 0xDE: case 0xDF:
                    case 0xE0: case 0xE1: case 0xE2: case 0xE3:
                    case 0xE4: case 0xE5: case 0xE6:
                    case 0xE8: case 0xE9: case 0xEA: case 0xEB:
                    case 0xEC: case 0xED: case 0xEE: case 0xEF:
                    case 0xF1: case 0xF2: case 0xF3: case 0xF4:
                    case 0xF5: case 0xF6:
                    case 0xF8: case 0xF9: case 0xFA: case 0xFB:
                    case 0xFC: case 0xFD: case 0xFE:
                        loadSize = 16; ripAllowed = true; break;
                    case 0x71: case 0x72: case 0x73:              // psll/psrl/psra imm groups
                        imm = 1; memAllowed = false; break;
                    case 0xD7:                                   // pmovmskb r, xmm — reg only
                        memAllowed = false; break;
                    default:
                        return false;
                }
            } else {
                if (op >= 0x40 && op <= 0x4F) {                  // cmovcc r, r/m
                    loadSize = w ? 8 : 4; ripAllowed = true;
                } else if (op >= 0x90 && op <= 0x9F) {           // setcc r/m8
                    loadSize = 0;
                } else if (op >= 0xC8 && op <= 0xCF) {           // bswap r — no ModRM
                    int len = (i - off) + 2;
                    if (off + len > limit) return false;
                    out.length = len;
                    return true;
                } else {
                    switch (op) {
                        case 0xAF:                               // imul r, r/m
                            loadSize = w ? 8 : 4; ripAllowed = true; break;
                        case 0xB6: case 0xBE:                    // movzx/movsx r, r/m8
                            loadSize = 1; ripAllowed = true; break;
                        case 0xB7: case 0xBF:                    // movzx/movsx r, r/m16
                            loadSize = 2; ripAllowed = true; break;
                        case 0xBC: case 0xBD:                    // bsf/bsr r, r/m
                            loadSize = w ? 8 : 4; ripAllowed = true; break;
                        case 0x1F:                               // multi-byte NOP — operand never accessed
                            ripAllowed = true; absOk = true; break;
                        case 0x2E: case 0x2F:                    // ucomiss/comiss xmm, xmm/m32
                            loadSize = 4; ripAllowed = true; break;
                        case 0x10: case 0x28:                    // movups/movaps load
                        case 0x14: case 0x15:                    // unpcklps/unpckhps
                        case 0x51:                              // sqrtps
                        case 0x54: case 0x55: case 0x56: case 0x57:
                        case 0x58: case 0x59:                    // packed add/mul
                        case 0x5B:                              // cvtdq2ps
                        case 0x5C: case 0x5D: case 0x5E: case 0x5F:
                            loadSize = 16; ripAllowed = true; break;
                        case 0x5A:                              // cvtps2pd reads two floats
                            loadSize = 8; ripAllowed = true; break;
                        case 0x12: case 0x16:                    // movlps/movhps m64 load (reg form: movhlps/movlhps)
                            loadSize = 8; ripAllowed = true; break;
                        case 0x11: case 0x29:                    // movups/movaps store
                        case 0x13: case 0x17:                    // movlps/movhps store
                            break;
                        case 0x50:                              // movmskps r, xmm — reg only
                            memAllowed = false; break;
                        case 0xA4: case 0xAC:                    // shld/shrd r/m, r, imm8 — RMW
                            imm = 1; break;
                        case 0xA5: case 0xAD:                    // shld/shrd r/m, r, cl — RMW
                            break;
                        case 0xC2: case 0xC6:                    // cmpps/shufps /r, imm8
                            imm = 1; loadSize = 16; ripAllowed = true; break;
                        default:
                            return false;
                    }
                }
            }
        } else {
            opLen = 1;

            if (prefix == 0x66) {
                // 16-bit operand-size forms: plain ALU/mov/test on i16 values.
                // Immediates shrink to imm16 for the 81/C7/acc-imm forms.
                if (b == 0x05 || b == 0x0D || b == 0x15 || b == 0x1D
                        || b == 0x25 || b == 0x2D || b == 0x35 || b == 0x3D
                        || b == 0xA9) {
                    int len = (i - off) + 3;                     // opcode + imm16, no ModRM
                    if (off + len > limit) return false;
                    out.length = len;
                    return true;
                }
                switch (b) {
                    case 0x01: case 0x03: case 0x09: case 0x0B:
                    case 0x21: case 0x23: case 0x29: case 0x2B:
                    case 0x31: case 0x33: case 0x39: case 0x3B:
                    case 0x85: case 0x89: case 0x8B:
                        if ((b & 2) != 0 || b == 0x8B) {         // load direction
                            loadSize = 2; ripAllowed = true;
                        }
                        break;
                    case 0x83: imm = 1; break;                   // ALU r/m16, imm8
                    case 0x81: imm = 2; break;                   // ALU r/m16, imm16
                    case 0xC7: {                                 // mov r/m16, imm16 (/0 only)
                        int mr = u(code, i + 1, limit);
                        if (mr < 0 || (mr & 0x38) != 0) return false;
                        imm = 2;
                        break;
                    }
                    default:
                        return false;
                }
            } else if (prefix != 0) {
                return false;                                    // F2/F3 outside 0F map
            } else {

            // ModRM-less opcodes.
            if (b == 0x90 || b == 0x98 || b == 0x99              // nop / cdqe / cqo
                    || (b >= 0x50 && b <= 0x5F)) {               // push/pop r64
                int len = (i - off) + 1;
                if (off + len > limit) return false;
                out.length = len;
                return true;
            }
            if (b >= 0xB8 && b <= 0xBF) {                        // mov r, imm32 / movabs r, imm64
                int len = (i - off) + 1 + (w ? 8 : 4);
                if (off + len > limit) return false;
                out.length = len;
                return true;
            }
            // ALU/test accumulator immediates have no ModRM byte.
            if (b == 0x04 || b == 0x0C || b == 0x14 || b == 0x1C
                    || b == 0x24 || b == 0x2C || b == 0x34 || b == 0x3C
                    || b == 0xA8) {
                int len = (i - off) + 2;
                if (off + len > limit) return false;
                out.length = len;
                return true;
            }
            if (b == 0x05 || b == 0x0D || b == 0x15 || b == 0x1D
                    || b == 0x25 || b == 0x2D || b == 0x35 || b == 0x3D
                    || b == 0xA9) {
                int len = (i - off) + 5;
                if (off + len > limit) return false;
                out.length = len;
                return true;
            }

            switch (b) {
                // ALU/mov/test, store or read-modify-write direction (m, r):
                // memory form ok, RIP form not (would alias a shared global).
                case 0x00: case 0x01: case 0x08: case 0x09:      // add / or
                case 0x20: case 0x21: case 0x28: case 0x29:      // and / sub
                case 0x30: case 0x31: case 0x38: case 0x39:      // xor / cmp
                case 0x84: case 0x85:                            // test
                case 0x88: case 0x89:                            // mov
                    break;
                // ALU/mov, load direction (r, m): RIP literal relocatable.
                case 0x02: case 0x03: case 0x0A: case 0x0B:
                case 0x22: case 0x23: case 0x2A: case 0x2B:
                case 0x32: case 0x33: case 0x3A: case 0x3B:
                case 0x8A: case 0x8B:
                    loadSize = (b & 1) == 0 ? 1 : (w ? 8 : 4);
                    ripAllowed = true;
                    break;
                case 0x63:                                       // movsxd r64, r/m32
                    loadSize = 4; ripAllowed = true; break;
                case 0x8D:                                       // lea — memory form only
                    // RIP form (lea r, [rip+disp]) materializes an address —
                    // compilers use it for table bases. Accepted with
                    // loadSize 0: the operand is never dereferenced here, and
                    // the disp32 is re-aimed at the original target after the
                    // nmethod is placed (rebase relocation).
                    regAllowed = false; absOk = true; ripAllowed = true; break;
                case 0x69:                                       // imul r, r/m, imm32
                    imm = 4; loadSize = w ? 8 : 4; ripAllowed = true; break;
                case 0x6B:                                       // imul r, r/m, imm8
                    imm = 1; loadSize = w ? 8 : 4; ripAllowed = true; break;
                case 0x80: case 0x83: imm = 1; break;            // ALU r/m, imm8
                case 0x81: imm = 4; break;                       // ALU r/m, imm32
                case 0xC0: case 0xC1: imm = 1; break;            // shift/rotate r/m, imm8
                case 0xD0: case 0xD1: case 0xD2: case 0xD3: break; // shift r/m, 1 / cl
                case 0xC6: case 0xC7: {                          // mov r/m, imm
                    // Only ModRM extension /0 is MOV. /7 encodes XABORT
                    // (C6 F8 ib) or XBEGIN (C7 F8 rel32), both control flow.
                    int mr = u(code, i + 1, limit);
                    if (mr < 0 || (mr & 0x38) != 0) return false;
                    imm = b == 0xC6 ? 1 : 4;
                    break;
                }
                case 0xF6: case 0xF7: {                          // grp3: test/not/neg/mul/imul/div/idiv
                    int mr = u(code, i + 1, limit);
                    if (mr < 0) return false;
                    int ext = (mr >> 3) & 7;
                    if (ext == 0 || ext == 1) {                  // TEST r/m, imm
                        imm = (b == 0xF6) ? 1 : 4;
                    }
                    break;
                }
                case 0xFE: case 0xFF: {                          // grp4/5: inc/dec only
                    int mr = u(code, i + 1, limit);
                    if (mr < 0) return false;
                    if (((mr >> 3) & 7) > 1) return false;       // /2../7: call/jmp/push
                    break;
                }
                default:
                    return false;
            }
            }
        }

        return finishModrm(code, off, limit, i + opLen, imm, loadSize,
                ripAllowed, memAllowed, regAllowed, absOk, out);
    }

    /**
     * VEX-encoded subset: AVX/AVX2 counterparts of the whitelisted SSE ops,
     * FMA (map 0F38 0x96-0xBF), vround/vblend/vinsert (map 0F3A), broadcasts,
     * BMI1/BMI2 GPR ops (andn/bzhi/pext/pdep/mulx/shlx/sarx/shrx/rorx/blsi...)
     * and vzeroupper. EVEX (AVX-512) is not decoded — such bodies stay on the
     * stub path. 32-byte (ymm) literals relocate fine: the pool aligns them to
     * 32 and HotSpot's CodeEntryAlignment keeps nmethod entries 32-aligned.
     */
    private static boolean decodeVex(byte[] code, int off, int limit, Insn out) {
        int i = off;
        int b0 = u(code, i, limit);
        int map;
        int pp;                     // 0=none, 1=66, 2=F3, 3=F2
        boolean w = false;
        int L;
        int opPos;
        if (b0 == 0xC5) {
            int b1 = u(code, i + 1, limit);
            if (b1 < 0) return false;
            map = 1;
            L = (b1 >> 2) & 1;
            pp = b1 & 3;
            opPos = i + 2;
        } else {
            int b1 = u(code, i + 1, limit);
            int b2 = u(code, i + 2, limit);
            if (b1 < 0 || b2 < 0) return false;
            map = b1 & 0x1F;
            w = (b2 & 0x80) != 0;
            L = (b2 >> 2) & 1;
            pp = b2 & 3;
            opPos = i + 3;
        }
        int op = u(code, opPos, limit);
        if (op < 0) return false;
        int vec = L == 1 ? 32 : 16;

        int imm = 0;
        int loadSize = 0;
        boolean ripAllowed = false;
        boolean memAllowed = true;
        boolean regAllowed = true;

        if (map == 1) {
            if (op == 0x77) {                                    // vzeroupper / vzeroall — no ModRM
                int len = (opPos + 1) - off;
                if (off + len > limit) return false;
                out.length = len;
                return true;
            }
            if (pp == 2 || pp == 3) {
                // Scalar (and a few F3/F2-prefixed vector) ops.
                int sz = pp == 2 ? 4 : 8;
                switch (op) {
                    case 0x10: loadSize = sz; ripAllowed = true; break;
                    case 0x11: break;
                    case 0x12: loadSize = pp == 2 ? vec : (L == 1 ? 32 : 8); ripAllowed = true; break;
                    case 0x16:
                        if (pp != 2) return false;
                        loadSize = vec; ripAllowed = true; break;
                    case 0x2A: loadSize = w ? 8 : 4; ripAllowed = true; break;
                    case 0x2C: case 0x2D:
                    case 0x51: case 0x58: case 0x59: case 0x5A:
                    case 0x5C: case 0x5D: case 0x5E: case 0x5F:
                        loadSize = sz; ripAllowed = true; break;
                    case 0x5B:
                        if (pp != 2) return false;               // vcvttps2dq
                        loadSize = vec; ripAllowed = true; break;
                    case 0x6F:                                   // vmovdqu load (F3)
                        if (pp != 2) return false;
                        loadSize = vec; ripAllowed = true; break;
                    case 0x70:                                   // vpshufhw/lw
                        imm = 1; loadSize = vec; ripAllowed = true; break;
                    case 0x7E:                                   // vmovq xmm, m64 (F3)
                        if (pp != 2) return false;
                        loadSize = 8; ripAllowed = true; break;
                    case 0x7F:                                   // vmovdqu store (F3)
                        if (pp != 2) return false;
                        break;
                    case 0xC2: imm = 1; loadSize = sz; ripAllowed = true; break;
                    case 0xE6: loadSize = pp == 2 ? vec / 2 : vec; ripAllowed = true; break;
                    default:
                        return false;
                }
            } else if (pp == 1) {
                switch (op) {
                    case 0x10: case 0x28: case 0x6F:
                        loadSize = vec; ripAllowed = true; break;
                    case 0x11: case 0x29: case 0x7F:
                        break;
                    case 0x14: case 0x15: case 0x51:
                    case 0x54: case 0x55: case 0x56: case 0x57:
                    case 0x58: case 0x59: case 0x5B:
                    case 0x5C: case 0x5D: case 0x5E: case 0x5F:
                        loadSize = vec; ripAllowed = true; break;
                    case 0x5A:                                   // vcvtpd2ps
                        loadSize = vec; ripAllowed = true; break;
                    case 0x2E: case 0x2F:
                        loadSize = 8; ripAllowed = true; break;
                    case 0x6E:                                   // vmovd/vmovq xmm, r/m
                        loadSize = w ? 8 : 4; ripAllowed = true; break;
                    case 0x7E: case 0xD6:                        // store direction
                        break;
                    case 0xC2: case 0xC6: case 0x70:
                        imm = 1; loadSize = vec; ripAllowed = true; break;
                    case 0x60: case 0x61: case 0x62: case 0x63:
                    case 0x64: case 0x65: case 0x66: case 0x67:
                    case 0x68: case 0x69: case 0x6A: case 0x6B:
                    case 0x6C: case 0x6D:
                    case 0x74: case 0x75: case 0x76:
                    case 0xD1: case 0xD2: case 0xD3: case 0xD4: case 0xD5:
                    case 0xD8: case 0xD9: case 0xDA: case 0xDB:
                    case 0xDC: case 0xDD: case 0xDE: case 0xDF:
                    case 0xE0: case 0xE1: case 0xE2: case 0xE3:
                    case 0xE4: case 0xE5: case 0xE6:
                    case 0xE8: case 0xE9: case 0xEA: case 0xEB:
                    case 0xEC: case 0xED: case 0xEE: case 0xEF:
                    case 0xF1: case 0xF2: case 0xF3: case 0xF4:
                    case 0xF5: case 0xF6:
                    case 0xF8: case 0xF9: case 0xFA: case 0xFB:
                    case 0xFC: case 0xFD: case 0xFE:
                        loadSize = vec; ripAllowed = true; break;
                    case 0x71: case 0x72: case 0x73:
                        imm = 1; memAllowed = false; break;
                    case 0xD7:
                        memAllowed = false; break;
                    default:
                        return false;
                }
            } else {
                switch (op) {
                    case 0x10: case 0x28:
                        loadSize = vec; ripAllowed = true; break;
                    case 0x11: case 0x29:
                        break;
                    case 0x14: case 0x15: case 0x51:
                    case 0x54: case 0x55: case 0x56: case 0x57:
                    case 0x58: case 0x59: case 0x5B:
                    case 0x5C: case 0x5D: case 0x5E: case 0x5F:
                        loadSize = vec; ripAllowed = true; break;
                    case 0x5A:                                   // vcvtps2pd reads half width
                        loadSize = vec / 2; ripAllowed = true; break;
                    case 0x2E: case 0x2F:
                        loadSize = 4; ripAllowed = true; break;
                    case 0x12: case 0x16:                        // vmovlps/vmovhps m64
                        loadSize = 8; ripAllowed = true; break;
                    case 0x13: case 0x17:
                        break;
                    case 0x50:
                        memAllowed = false; break;
                    case 0xC2: case 0xC6:
                        imm = 1; loadSize = vec; ripAllowed = true; break;
                    default:
                        return false;
                }
            }
        } else if (map == 2) {
            boolean isFma = (op >= 0x96 && op <= 0x9F)
                    || (op >= 0xA6 && op <= 0xAF)
                    || (op >= 0xB6 && op <= 0xBF);
            if (isFma) {
                if (pp != 1) return false;
                int lo = op & 0xF;
                boolean scalar = lo == 0x9 || lo == 0xB || lo == 0xD || lo == 0xF;
                loadSize = scalar ? (w ? 8 : 4) : vec;
                ripAllowed = true;
            } else {
                switch (op) {
                    case 0x18: loadSize = 4; ripAllowed = true; break;   // vbroadcastss
                    case 0x19: loadSize = 8; ripAllowed = true; break;   // vbroadcastsd
                    case 0x1A: loadSize = 16; ripAllowed = true; break;  // vbroadcastf128
                    case 0x58: loadSize = 4; ripAllowed = true; break;   // vpbroadcastd
                    case 0x59: loadSize = 8; ripAllowed = true; break;   // vpbroadcastq
                    case 0x78: loadSize = 1; ripAllowed = true; break;   // vpbroadcastb
                    case 0x79: loadSize = 2; ripAllowed = true; break;   // vpbroadcastw
                    case 0x00:                                           // vpshufb
                    case 0x17:                                           // vptest
                    case 0x1C: case 0x1D: case 0x1E:                     // vpabsb/w/d
                    case 0x28: case 0x29: case 0x2B:                     // vpmuldq/vpcmpeqq/vpackusdw
                    case 0x37:                                           // vpcmpgtq
                    case 0x38: case 0x39: case 0x3A: case 0x3B:          // vpmins*/vpminu*
                    case 0x3C: case 0x3D: case 0x3E: case 0x3F:          // vpmaxs*/vpmaxu*
                    case 0x40:                                           // vpmulld
                    case 0x45: case 0x46: case 0x47:                     // vpsrlv/vpsrav/vpsllv
                        loadSize = vec; ripAllowed = true; break;
                    case 0x20: case 0x23: case 0x25:                     // vpmovsxbw/wd/dq
                    case 0x30: case 0x33: case 0x35:                     // vpmovzx
                        loadSize = vec / 2; ripAllowed = true; break;
                    case 0x21: case 0x24: case 0x31: case 0x34:          // vpmovs/zxbd/wq
                        loadSize = vec / 4; ripAllowed = true; break;
                    case 0x22: case 0x32:                                // vpmovs/zxbq
                        loadSize = vec / 8; ripAllowed = true; break;
                    // BMI1/BMI2 on GPRs.
                    case 0xF2:                                           // andn
                        if (pp != 0) return false;
                        loadSize = w ? 8 : 4; ripAllowed = true; break;
                    case 0xF3: {                                         // blsr/blsmsk/blsi (/1,/2,/3)
                        if (pp != 0) return false;
                        int mr = u(code, opPos + 1, limit);
                        if (mr < 0) return false;
                        int ext = (mr >> 3) & 7;
                        if (ext < 1 || ext > 3) return false;
                        loadSize = w ? 8 : 4; ripAllowed = true; break;
                    }
                    case 0xF5:                                           // bzhi/pext/pdep
                    case 0xF7:                                           // bextr/shlx/sarx/shrx
                        loadSize = w ? 8 : 4; ripAllowed = true; break;
                    case 0xF6:                                           // mulx (F2 only)
                        if (pp != 3) return false;
                        loadSize = w ? 8 : 4; ripAllowed = true; break;
                    default:
                        return false;
                }
            }
        } else if (map == 3) {
            imm = 1;                                             // all accepted 0F3A ops carry imm8
            switch (op) {
                case 0x00: case 0x01:                            // vpermq/vpermpd
                    loadSize = 32; ripAllowed = true; break;
                case 0x02:                                       // vpblendd
                case 0x06:                                       // vperm2f128
                case 0x46:                                       // vperm2i128
                case 0x08: case 0x09:                            // vroundps/pd
                case 0x0C: case 0x0D: case 0x0E: case 0x0F:      // vblendps/pd, vpblendw, vpalignr
                case 0x4A: case 0x4B: case 0x4C:                 // vblendvps/pd, vpblendvb
                    loadSize = vec; ripAllowed = true; break;
                case 0x0A: loadSize = 4; ripAllowed = true; break;   // vroundss
                case 0x0B: loadSize = 8; ripAllowed = true; break;   // vroundsd
                case 0x18: case 0x38:                            // vinsertf128/vinserti128
                    loadSize = 16; ripAllowed = true; break;
                case 0x19: case 0x39:                            // vextractf128/i128 — store dir
                    break;
                case 0x14: case 0x15: case 0x16: case 0x17:      // vpextr/vextractps — store dir
                    break;
                case 0x20: loadSize = 1; ripAllowed = true; break;   // vpinsrb
                case 0x21: loadSize = 4; ripAllowed = true; break;   // vinsertps
                case 0x22: loadSize = w ? 8 : 4; ripAllowed = true; break; // vpinsrd/q
                case 0xF0:                                       // rorx (F2 only)
                    if (pp != 3) return false;
                    loadSize = w ? 8 : 4; ripAllowed = true; break;
                default:
                    return false;
            }
        } else {
            return false;
        }

        return finishModrm(code, off, limit, opPos + 1, imm, loadSize,
                ripAllowed, memAllowed, regAllowed, out);
    }

    /**
     * Legacy three-byte maps 0F 38 / 0F 3A — the 66-prefixed SSSE3/SSE4.1
     * subset mirroring the VEX tables (round, blend, pmovsx/zx, pmulld, ...).
     */
    private static boolean decodeLegacy38or3A(byte[] code, int off, int limit,
                                              int pos0F, int prefix, boolean w,
                                              int mapByte, Insn out) {
        if (prefix != 0x66) return false;
        int op = u(code, pos0F + 2, limit);
        if (op < 0) return false;

        int imm = 0;
        int loadSize = 0;
        boolean ripAllowed = false;
        boolean memAllowed = true;

        if (mapByte == 0x38) {
            switch (op) {
                case 0x00:                                       // pshufb
                case 0x10: case 0x14: case 0x15:                 // pblendvb/blendvps/blendvpd (implicit xmm0)
                case 0x17:                                       // ptest
                case 0x1C: case 0x1D: case 0x1E:                 // pabs
                case 0x28: case 0x29: case 0x2B:                 // pmuldq/pcmpeqq/packusdw
                case 0x37:                                       // pcmpgtq
                case 0x38: case 0x39: case 0x3A: case 0x3B:
                case 0x3C: case 0x3D: case 0x3E: case 0x3F:      // pmin/pmax families
                case 0x40:                                       // pmulld
                    loadSize = 16; ripAllowed = true; break;
                case 0x20: case 0x23: case 0x25:
                case 0x30: case 0x33: case 0x35:
                    loadSize = 8; ripAllowed = true; break;
                case 0x21: case 0x24: case 0x31: case 0x34:
                    loadSize = 4; ripAllowed = true; break;
                case 0x22: case 0x32:
                    loadSize = 2; ripAllowed = true; break;
                default:
                    return false;
            }
        } else {
            imm = 1;
            switch (op) {
                case 0x08: case 0x09:                            // roundps/pd
                case 0x0C: case 0x0D: case 0x0E: case 0x0F:      // blendps/pd, pblendw, palignr
                    loadSize = 16; ripAllowed = true; break;
                case 0x0A: loadSize = 4; ripAllowed = true; break;   // roundss
                case 0x0B: loadSize = 8; ripAllowed = true; break;   // roundsd
                case 0x14: case 0x15: case 0x16: case 0x17:      // pextr/extractps — store dir
                    break;
                case 0x20: loadSize = 1; ripAllowed = true; break;   // pinsrb
                case 0x21: loadSize = 4; ripAllowed = true; break;   // insertps
                case 0x22: loadSize = w ? 8 : 4; ripAllowed = true; break; // pinsrd/q
                default:
                    return false;
            }
        }

        return finishModrm(code, off, limit, pos0F + 3, imm, loadSize,
                ripAllowed, memAllowed, true, out);
    }

    /**
     * Length of an alignment-padding instruction at {@code off}:
     * {@code (66|2E|3E)* 90}, {@code (66|2E|3E)* 0F 1F /0},
     * {@code F3 90} (pause), or the CET landing pads {@code ENDBR64} and
     * {@code ENDBR32}. Returns 0 when the bytes are not one of these forms.
     */
    private static int paddingNopLength(byte[] code, int off, int limit) {
        int p = off;
        int c = u(code, p, limit);
        if (c == 0xF3) {
            int c1 = u(code, p + 1, limit);
            if (c1 == 0x90) {
                return 2;
            }
            if (c1 == 0x0F && u(code, p + 2, limit) == 0x1E) {
                int endbr = u(code, p + 3, limit);
                return endbr == 0xFA || endbr == 0xFB ? 4 : 0;
            }
            return 0;
        }
        while (c == 0x66 || c == 0x2E || c == 0x3E) {
            c = u(code, ++p, limit);
        }
        if (p == off) return 0;
        if (c == 0x90) {
            return p + 1 - off;
        }
        if (c == 0x0F && u(code, p + 1, limit) == 0x1F) {
            Insn tmp = new Insn();
            if (!finishModrm(code, off, limit, p + 2, 0, 0, true, true, true, true, tmp)) {
                return 0;
            }
            return tmp.length;
        }
        return 0;
    }

    /** ModRM (+ SIB / disp / imm) processing shared by every opcode map. */
    private static boolean finishModrm(byte[] code, int off, int limit, int mrPos,
                                       int imm, int loadSize, boolean ripAllowed,
                                       boolean memAllowed, boolean regAllowed, Insn out) {
        return finishModrm(code, off, limit, mrPos, imm, loadSize,
                ripAllowed, memAllowed, regAllowed, false, out);
    }

    private static boolean finishModrm(byte[] code, int off, int limit, int mrPos,
                                       int imm, int loadSize, boolean ripAllowed,
                                       boolean memAllowed, boolean regAllowed,
                                       boolean absOk, Insn out) {
        int modrm = u(code, mrPos, limit);
        if (modrm < 0) return false;
        int mod = modrm >>> 6;
        int rm = modrm & 7;
        int tail;
        if (mod == 3) {
            if (!regAllowed) return false;
            tail = 1;
        } else {
            if (!memAllowed) return false;
            if (mod == 0 && rm == 5) {                           // RIP-relative
                if (!ripAllowed) return false;
                out.ripDispOff = (mrPos + 1) - off;
                out.ripLoadSize = loadSize;
                tail = 5;
            } else if (rm == 4) {                                // SIB byte
                int sib = u(code, mrPos + 1, limit);
                if (sib < 0) return false;
                if (mod == 0 && (sib & 7) == 5) {
                    // disp32(+index) with no base register: absolute
                    // addressing for real memory operands. Allowed only for
                    // operations that never dereference (LEA, NOP), where the
                    // form is pure position-independent arithmetic like
                    // lea r, [disp32 + index*scale]. Carries a disp32.
                    if (!absOk) return false;
                    tail = 2 + 4;
                } else {
                    tail = 2 + (mod == 1 ? 1 : mod == 2 ? 4 : 0);
                }
            } else {
                tail = 1 + (mod == 1 ? 1 : mod == 2 ? 4 : 0);
            }
        }
        int len = (mrPos - off) + tail + imm;
        if (off + len > limit) return false;
        out.length = len;
        return true;
    }

    private static int u(byte[] code, int idx, int limit) {
        return idx < limit ? code[idx] & 0xFF : -1;
    }


    private static void checkMethodType(Method m) {
        int modifiers = m.getModifiers();
        if (!Modifier.isStatic(modifiers) || !Modifier.isNative(modifiers)) {
            throw new IllegalArgumentException("Method must be static native: " + m);
        }
        if (Modifier.isSynchronized(modifiers)) {
            // Installing a raw nmethod bypasses HotSpot's synchronized-native
            // wrapper, so accepting this modifier would silently drop locking.
            throw new IllegalArgumentException("Synchronized native methods are not supported: " + m);
        }
    }

    private static void installHexCode(Method m, String hex) {
        checkMethodType(m);
        ByteBuffer buf = rawCodeBuffer(HexCode.decodedLength(hex));
        emitEntryBarrierPrologue(buf);
        HexCode.emit(buf, hex);
        installCode(m, buf, null, null, false);
    }

    private static ByteBuffer rawCodeBuffer(int length) {
        // AMD64's 22-byte entry + 24-byte slow path also covers legacy trailers.
        // Leave headroom for JVMCI's signed, word-aligned code size, as before.
        if (length > Integer.MAX_VALUE - 96) throw new IllegalArgumentException("Machine code too large for JVMCI");
        return ByteBuffer.allocate(MARK_ENTRY_BARRIER_PATCH == null ? length : length + 46)
                .order(ByteOrder.nativeOrder());
    }

    public static void installCode(Method m, byte[] code) {
        installCode(m, code, code.length);
    }

    public static void installCode(Method m, byte[] code, int length) {
        checkMethodType(m);
        Objects.checkFromIndexSize(0, length, code.length);
        ByteBuffer buf = rawCodeBuffer(length);
        emitEntryBarrierPrologue(buf);
        buf.put(code, 0, length);
        installCode(m, buf, null, null, false);
    }

    // All executable AMD64 entry barriers have the same prefix. Only the
    // positions in the out-of-line slow path are specific to a method.
    private static final int AMD64_ENTRY_PATCH_OFFSET = 8;  // 5-byte verified-entry NOP + 3-byte NOP
    private static final int AMD64_ENTRY_BRANCH_DISP = AMD64_ENTRY_PATCH_OFFSET + 8 + 2; // CMP, JNE opcode
    private static final int AMD64_ENTRY_CONTINUATION = AMD64_ENTRY_BRANCH_DISP + 4;

    // EXPERIMENTAL, measurement only: -Dnalim.unsafe.legacybarrier=true drops
    // the executable AMD64 entry-barrier prologue and installs the legacy
    // never-executed trailing barrier instead (the pre-JDK-26 form). Saves the
    // per-call CMP+JNE but forfeits cold-flush protection on JDK 26+ and any
    // future GC that relies on nmethod entry barriers actually executing.
    private static final boolean LEGACY_BARRIER = Boolean.getBoolean("nalim.unsafe.legacybarrier");
    private static final boolean EXECUTABLE_AMD64_BARRIER = MARK_ENTRY_BARRIER_PATCH != null && AMD64 && !LEGACY_BARRIER;
    private static final Mark AMD64_ENTRY_PATCH = EXECUTABLE_AMD64_BARRIER
            ? new Mark(AMD64_ENTRY_PATCH_OFFSET, MARK_ENTRY_BARRIER_PATCH) : null;

    private static void emitEntryBarrierPrologue(ByteBuffer buf) {
        if (!EXECUTABLE_AMD64_BARRIER) return;
        if (buf.position() != 0) {
            throw new IllegalStateException("AMD64 entry barrier must be the first instruction");
        }
        if (THREAD_DISARMED_OFFSET == null || NMETHOD_ENTRY_BARRIER == null || NMETHOD_ENTRY_BARRIER == 0) {
            throw new IllegalStateException("HotSpot did not expose the nmethod entry-barrier config");
        }
        int threadOffset = THREAD_DISARMED_OFFSET;
        if ((byte) threadOffset != threadOffset) {
            throw new IllegalStateException("nmethod disarmed offset does not fit disp8: " + threadOffset);
        }

        // Keep verified-entry patching and GC barrier patching disjoint: HotSpot
        // may replace the first five bytes with a NativeJump when the nmethod is
        // made non-entrant, while the barrier immediate remains independently
        // patchable at patchOffset below.
        // The alignment NOP is a separate instruction from the five-byte entry
        // NOP. Emit their existing little-endian encodings in a single store.
        buf.putLong(LEGACY_PAD ? 0x9090900000441f0fL : 0x001f0f0000441f0fL);

        // Canonical eight-byte CMP. Its imm32 starts four bytes into the
        // instruction and is naturally aligned for atomic GC patching.
        buf.put((byte) 0x41).put((byte) 0x81).put((byte) 0x7F).put((byte) threadOffset);
        buf.putInt(0);                                           // armed epoch; HotSpot patches it

        // Fast path falls through. The rare armed/not-entrant case gets a real
        // 32-byte frame in the out-of-line block appended after the method body.
        buf.put((byte) 0x0F).put((byte) 0x85).putInt(0);         // jne rel32
    }

    // A/B diagnostics: -Dnalim.legacypad=true restores the original 1-byte-nop
    // padding so the coalesced-NOP prologue can be measured in isolation.
    private static final boolean LEGACY_PAD = Boolean.getBoolean("nalim.legacypad");

    private static Site[] finishEntryBarrier(ByteBuffer buf, Call directJump) {
        // Put the frame-dismantling block before FRAME_COMPLETE. An asynchronous
        // walk either sees the exact slow frame at the call or conservatively
        // treats the frameless hot body / tiny epilogue as not frame-complete.
        int epilogueOffset = buf.position();
        buf.put((byte) 0xC9);                                    // leave
        buf.put((byte) 0xE9);                                    // jmp hot continuation
        int epilogueJumpDisp = buf.position();
        buf.putInt(AMD64_ENTRY_CONTINUATION - (epilogueJumpDisp + 4));

        int slowPathOffset = buf.position();
        buf.putInt(AMD64_ENTRY_BRANCH_DISP, slowPathOffset - AMD64_ENTRY_CONTINUATION);
        buf.put((byte) 0x55);                                    // push rbp
        buf.put((byte) 0x48).put((byte) 0x89).put((byte) 0xE5);  // mov rbp, rsp
        buf.put((byte) 0x48).put((byte) 0x83).put((byte) 0xEC).put((byte) 0x10); // sub rsp, 16

        int callOffset = buf.position();
        buf.put((byte) 0xE8).putInt(0);
        Call slowCall = new Call(new HotSpotForeignCallTarget(NMETHOD_ENTRY_BARRIER), callOffset, 5, true, null);

        buf.put((byte) 0xE9);                                    // jmp slow epilogue
        int slowReturnDisp = buf.position();
        buf.putInt(epilogueOffset - (slowReturnDisp + 4));

        Site[] sites = new Site[2 + (directJump == null ? 0 : 1) + (MARK_FRAME_COMPLETE == null ? 0 : 1)];
        int i = 0;
        sites[i++] = AMD64_ENTRY_PATCH;
        if (directJump != null) sites[i++] = directJump;
        if (MARK_FRAME_COMPLETE != null) sites[i++] = new Mark(callOffset, MARK_FRAME_COMPLETE);
        sites[i] = slowCall;
        return sites;
    }

    private static final Site[] NO_SITES = new Site[0];
    private static final Assumptions.Assumption[] NO_ASSUMPTIONS = new Assumptions.Assumption[0];
    private static final HotSpotCompiledCode.Comment[] NO_COMMENTS = new HotSpotCompiledCode.Comment[0];
    private static final DataPatch[] NO_PATCHES = new DataPatch[0];
    private static final byte[] NO_DATA = new byte[0];

    private static void installCode(Method m, ByteBuffer buf, Call directJump, Fixups rebases, boolean sharedBuffer) {
        Site[] sites;
        int totalFrameSize = 0;
        if (EXECUTABLE_AMD64_BARRIER) {
            sites = finishEntryBarrier(buf, directJump);
            totalFrameSize = 32; // return address + saved rbp + 16-byte aligned slow frame
        } else if (MARK_ENTRY_BARRIER_PATCH != null) {
            int markOffset = callingConvention.emitNMethodBarrier(buf);
            // Emit both FRAME_COMPLETE (id=6) and ENTRY_BARRIER_PATCH (id=7)
            // at the barrier offset, matching the canonical form used by Graal
            // and OpenJDK's JTreg AMD64TestAssembler.emitNMethodEntryBarrier.
            if (MARK_FRAME_COMPLETE != null) {
                sites = directJump != null
                        ? new Site[] {
                                directJump,
                                new Mark(markOffset, MARK_FRAME_COMPLETE),
                                new Mark(markOffset, MARK_ENTRY_BARRIER_PATCH)
                        }
                        : new Site[] {
                                new Mark(markOffset, MARK_FRAME_COMPLETE),
                                new Mark(markOffset, MARK_ENTRY_BARRIER_PATCH)
                        };
            } else {
                sites = directJump != null
                        ? new Site[] { directJump, new Mark(markOffset, MARK_ENTRY_BARRIER_PATCH) }
                        : new Site[] { new Mark(markOffset, MARK_ENTRY_BARRIER_PATCH) };
            }
        } else {
            // Pre-Loom JDK — installCode0 does not require an entry barrier.
            sites = directJump != null ? new Site[] { directJump } : NO_SITES;
        }

        HotSpotResolvedJavaMethod rm = (HotSpotResolvedJavaMethod) jvmci.getMetaAccess().lookupJavaMethod(m);
        int compileId = rm.allocateCompileId(JVMCICompiler.INVOCATION_ENTRY_BCI);

        // JVMCI can publish CompiledCode to listeners. Detach the exact emitted
        // bytes before reusing a class-local emitter for the next method.
        int codeSize = buf.position();
        byte[] code = sharedBuffer ? Arrays.copyOf(buf.array(), codeSize) : buf.array();

        HotSpotCompiledNmethod nm = new HotSpotCompiledNmethod(
                m.getName(),
                code,
                codeSize,
                sites,
                NO_ASSUMPTIONS,
                new ResolvedJavaMethod[] { rm },
                NO_COMMENTS,
                NO_DATA,
                1,
                NO_PATCHES,
                true,
                totalFrameSize,
                null,
                rm,
                JVMCICompiler.INVOCATION_ENTRY_BCI,
                compileId,
                0,
                false
        );

        InstalledCode installed = codeCache.setDefaultCode(rm, nm);
        rm.setNotInlinableOrCompilable();

        applyRebaseRelocations(m, buf, installed, rebases);

        if (DUMP_DIR != null) {
            dumpInstalledCode(m, installed);
        }
    }

    /**
     * Re-aim RIP-relative disp32 fields recorded by the inliner at their
     * original operands, now that the nmethod address is known. Entries are
     * {bufOffsetOfDisp32, bufOffsetOfNextInsn, absoluteTargetAddress}.
     * <p>
     * Assumes the installed instructions are a byte-identical copy of the
     * buffer starting at {@link InstalledCode#getStart()} — verified against
     * the surrounding instruction bytes BEFORE anything is written (bytes
     * belonging to any rebase disp32 field are excluded from the check, so
     * adjacent rebased instructions cannot fail against each other's patch).
     * Reachability was pre-gated with {@code getMaxCallTargetOffset}, so the
     * final displacement always fits. On any inconsistency the nmethod is
     * invalidated before throwing — a stale placeholder must never stay
     * callable.
     * <p>
     * Contract: linkMethod must complete before the method is invoked from
     * another thread. Link inside the holder class's static initializer (the
     * JVM serializes class init) — otherwise a racing caller could execute
     * the body while displacements still hold the placeholder value.
     */
    private static void applyRebaseRelocations(
            Method m, ByteBuffer buf, InstalledCode installed, Fixups rebases) {
        if (rebases == null || rebases.count == 0) {
            return;
        }
        try {
            long start = installed.getStart();
            if (start == 0) {
                throw new IllegalStateException("nalim: no code address after install: " + m);
            }
            // Pass 1: verify the installed image mirrors the buffer around
            // every disp field, skipping bytes inside ANY recorded disp field.
            for (int r = 0; r < rebases.count; r++) {
                int dispOff = rebases.disp[r];
                for (int k = -4; k < 8; k++) {
                    int p = dispOff + k;
                    if (p < 0 || p >= buf.position() || insideRebaseField(p, rebases)) {
                        continue;
                    }
                    if (NativeMemory.unsafe.getByte(start + p) != buf.get(p)) {
                        throw new IllegalStateException(
                                "nalim: installed code does not mirror the buffer at +0x"
                                        + Integer.toHexString(p) + ": " + m);
                    }
                }
            }
            // Pass 2: range-check every displacement, then write them all.
            for (int r = 0; r < rebases.count; r++) {
                long disp = rebases.target[r] - (start + rebases.next[r]);
                if (disp < Integer.MIN_VALUE || disp > Integer.MAX_VALUE) {
                    throw new IllegalStateException(
                            "nalim: rebase target out of rel32 range: " + m);
                }
            }
            for (int r = 0; r < rebases.count; r++) {
                NativeMemory.unsafe.putInt(start + rebases.disp[r],
                        (int) (rebases.target[r] - (start + rebases.next[r])));
            }
        } catch (Throwable t) {
            // The nmethod is already the method's default code; never leave a
            // placeholder displacement callable.
            try {
                installed.invalidate();
            } catch (Throwable suppressed) {
                // invalidation is best-effort; the original failure wins
            }
            throw t;
        }
    }

    // Multi-byte NOP (0F 1F /0) with a RIP-form operand: the operand is never
    // accessed, so the stale disp32 may be copied verbatim — relocating it
    // would waste rebase slots and could veto inlining on reachability.
    private static boolean isRipNop(byte[] code, int off) {
        int j = off;
        while (j < code.length - 1) {
            int b = code[j] & 0xFF;
            if (b == 0x66 || b == 0x2E || b == 0x3E || (b & 0xF0) == 0x40) {
                j++;
            } else {
                break;
            }
        }
        return j < code.length - 1
                && (code[j] & 0xFF) == 0x0F && (code[j + 1] & 0xFF) == 0x1F;
    }

    private static boolean insideRebaseField(int p, Fixups rebases) {
        for (int r = 0; r < rebases.count; r++) {
            int off = rebases.disp[r];
            if (p >= off && p < off + 4) {
                return true;
            }
        }
        return false;
    }

    // Diagnostics: -Dnalim.dump=<dir> writes every installed nmethod body
    // (after JVMCI relocation, i.e. exactly the bytes the CPU executes) to
    // <dir>/<Class>.<method>.bin. Inspect with:
    //   objdump -D -b binary -m i386:x86-64 <file>
    private static final String DUMP_DIR = System.getProperty("nalim.dump");

    private static void dumpInstalledCode(Method m, InstalledCode installed) {
        try {
            byte[] code = installed.getCode();
            if (code == null) {
                return;
            }
            java.nio.file.Path dir = Paths.get(DUMP_DIR);
            Files.createDirectories(dir);
            String name = m.getDeclaringClass().getSimpleName() + "." + m.getName() + ".bin";
            Files.write(dir.resolve(name), code);
            System.out.printf("[nalim] dumped %s: %d bytes at 0x%x%n",
                    name, code.length, installed.getStart());
        } catch (Throwable t) {
            System.err.println("[nalim] dump failed for " + m + ": " + t);
        }
    }
}
