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

import jdk.vm.ci.code.site.DataPatch;
import jdk.vm.ci.code.site.Mark;
import jdk.vm.ci.code.site.Site;
import jdk.vm.ci.hotspot.HotSpotCompiledCode;
import jdk.vm.ci.hotspot.HotSpotCompiledNmethod;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime;
import jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod;
import jdk.vm.ci.hotspot.HotSpotVMConfigAccess;
import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.runtime.JVMCI;
import jdk.vm.ci.runtime.JVMCIBackend;
import jdk.vm.ci.runtime.JVMCICompiler;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Links native methods to the provided machine code using JVMCI.
 */
public class Linker {
    // Open jdk.internal.vm.ci/jdk.vm.ci.* to our unnamed module BEFORE any
    // jdk.vm.ci.* type is first resolved by the JVM. This eliminates the need
    // for the five --add-exports flags or -javaagent:nalim.jar; only
    // -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI remain mandatory.
    // The static block runs first in clinit order, before the field initializers
    // below that mention JVMCIBackend / JVMCI.
    static {
        ModuleOpener.openJvmci();
    }

    private static final JVMCIBackend jvmci = JVMCI.getRuntime().getHostJVMCIBackend();
    private static final ConcurrentHashMap<String, Boolean> nativeLibraries = new ConcurrentHashMap<>();
    private static final CallingConvention callingConvention = CallingConvention.getInstance();

    // JDK 21+ requires every installed nmethod to carry an entry barrier
    // (Loom / JEP 444 made BarrierSetNMethod mandatory for every GC). The
    // values are queried from HotSpotVMConfigAccess so we track any future
    // reshuffling of the enum in jvmciCodeInstaller.hpp.
    // On JDK ≤ 20 the constants are absent — lookupMarkId returns null and
    // we install with the legacy empty-Site[] form.
    private static final Integer MARK_FRAME_COMPLETE = lookupMarkId("CodeInstaller::FRAME_COMPLETE");
    private static final Integer MARK_ENTRY_BARRIER_PATCH = lookupMarkId("CodeInstaller::ENTRY_BARRIER_PATCH");

    private static Integer lookupMarkId(String name) {
        try {
            HotSpotVMConfigAccess cfg = new HotSpotVMConfigAccess(HotSpotJVMCIRuntime.runtime().getConfigStore());
            return cfg.getConstant(name, Integer.class);
        } catch (Throwable t) {
            return null;
        }
    }

    static {
        loadLibrary("java");
    }

    public static void loadLibrary(String name) {
        if (nativeLibraries.putIfAbsent(name, Boolean.TRUE) == null) {
            if (name.indexOf('/') >= 0 || name.indexOf('\\') > 0) {
                System.load(name);
            } else {
                System.loadLibrary(name);
            }
        }
    }

    // ClassLoader.findNative signature evolved across JDK versions:
    //   JDK ≤ 21:  static long findNative(ClassLoader, String)
    //   JDK ≥ 22:  static long findNative(ClassLoader, Class<?>, String, String)
    // We resolve once at class init, then dispatch on parameter count.
    private static final Method FIND_NATIVE = resolveFindNative();

    private static Method resolveFindNative() {
        try {
            return JavaInternals.getPrivateMethod(ClassLoader.class, "findNative",
                    ClassLoader.class, Class.class, String.class, String.class);
        } catch (RuntimeException e1) {
            try {
                return JavaInternals.getPrivateMethod(ClassLoader.class, "findNative",
                        ClassLoader.class, String.class);
            } catch (RuntimeException e2) {
                throw new IllegalStateException("Cannot resolve ClassLoader.findNative", e2);
            }
        }
    }

    public static long findAddress(String symbol) {
        ClassLoader cl = Linker.class.getClassLoader();
        try {
            if (FIND_NATIVE.getParameterCount() == 4) {
                // JDK 22+: (loader, caller, entryName, javaName).
                // entryName is the symbol passed to dlsym; javaName is for diagnostics.
                return (long) FIND_NATIVE.invoke(null, cl, Linker.class, symbol, symbol);
            } else {
                return (long) FIND_NATIVE.invoke(null, cl, symbol);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void linkClass(Class<?> c) {
        Library library = c.getAnnotation(Library.class);
        if (library != null) {
            loadLibrary(library.value());
        }

        for (Method m : c.getDeclaredMethods()) {
            if (m.getAnnotation(Link.class) != null || m.getAnnotation(Code.class) != null) {
                linkMethod(m);
            }
        }
    }

    public static void linkMethod(Method m) {
        checkMethodType(m);

        Code code = m.getAnnotation(Code.class);
        if (code != null) {
            installCode(m, parseHex(code.value()));
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

        ByteBuffer buf = ByteBuffer.allocate(128).order(ByteOrder.nativeOrder());
        if (!naked) {
            callingConvention.javaToNative(buf, m.getParameterTypes(), m.getParameterAnnotations());
        }
        callingConvention.emitCall(buf, address);

        installCode(m, buf);
    }

    private static void checkMethodType(Method m) {
        int modifiers = m.getModifiers();
        if (!Modifier.isStatic(modifiers) || !Modifier.isNative(modifiers)) {
            throw new IllegalArgumentException("Method must be static native: " + m);
        }
    }

    private static byte[] parseHex(String hex) {
        hex = hex.replaceAll("\\s+", "");

        int length = hex.length();
        if ((length & 1) != 0) {
            throw new IllegalArgumentException("Invalid hex string");
        }

        byte[] code = new byte[length / 2];
        for (int i = 0; i < code.length; i++) {
            code[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return code;
    }

    public static void installCode(Method m, byte[] code) {
        installCode(m, code, code.length);
    }

    public static void installCode(Method m, byte[] code, int length) {
        // Allocate with room for the trailing entry barrier (max ~12 bytes
        // including NOP padding) so emitNMethodBarrier never needs to realloc.
        ByteBuffer buf = ByteBuffer.allocate(length + 32).order(ByteOrder.nativeOrder());
        buf.put(code, 0, length);
        installCode(m, buf);
    }

    private static void installCode(Method m, ByteBuffer buf) {
        Site[] sites;
        if (MARK_ENTRY_BARRIER_PATCH != null) {
            int markOffset = callingConvention.emitNMethodBarrier(buf);
            // Emit both FRAME_COMPLETE (id=6) and ENTRY_BARRIER_PATCH (id=7)
            // at the barrier offset, matching the canonical form used by Graal
            // and OpenJDK's JTreg AMD64TestAssembler.emitNMethodEntryBarrier.
            sites = MARK_FRAME_COMPLETE != null
                    ? new Site[] {
                            new Mark(markOffset, MARK_FRAME_COMPLETE),
                            new Mark(markOffset, MARK_ENTRY_BARRIER_PATCH)
                    }
                    : new Site[] { new Mark(markOffset, MARK_ENTRY_BARRIER_PATCH) };
        } else {
            // Pre-Loom JDK — installCode0 does not require an entry barrier.
            sites = new Site[0];
        }

        ResolvedJavaMethod rm = jvmci.getMetaAccess().lookupJavaMethod(m);

        HotSpotCompiledNmethod nm = new HotSpotCompiledNmethod(
                m.getName(),
                buf.array(),
                buf.position(),
                sites,
                new Assumptions.Assumption[0],
                new ResolvedJavaMethod[0],
                new HotSpotCompiledCode.Comment[0],
                new byte[0],
                1,
                new DataPatch[0],
                true,
                0,
                null,
                (HotSpotResolvedJavaMethod) rm,
                JVMCICompiler.INVOCATION_ENTRY_BCI,
                1,
                0,
                false
        );

        jvmci.getCodeCache().setDefaultCode(rm, nm);
    }
}
