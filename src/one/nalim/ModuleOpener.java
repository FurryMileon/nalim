/*
 * Copyright (C) 2026
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package one.nalim;

import java.lang.reflect.Method;

/**
 * Opens {@code jdk.internal.vm.ci/jdk.vm.ci.*} packages to the calling unnamed modules
 * at runtime, without needing the Nalim Java agent or the {@code --add-exports} JVM
 * flags. This makes Nalim usable inside isolated module layers (e.g. Fabric's knot
 * class loader) with only {@code -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI}.
 * <p>
 * Mechanism: locate {@code Module.implAddExports(String, Module)} reflectively, bypass
 * the {@code setAccessible} check via {@link JavaInternals#setAccessible} (which writes
 * the {@code AccessibleObject.override} field through {@code Unsafe}), and invoke it
 * for each JVMCI subpackage targeting the platform- and system-classloader unnamed
 * modules. This deliberately mimics what {@link Agent#premain} does via the official
 * {@code Instrumentation} API.
 * <p>
 * Crucially, this class itself does NOT reference any {@code jdk.vm.ci.*} type, so it
 * can be loaded and executed BEFORE the JVMCI packages are open.
 */
public final class ModuleOpener {

    private static final String[] JVMCI_PACKAGES = {
            "jdk.vm.ci.code",
            "jdk.vm.ci.code.site",
            "jdk.vm.ci.hotspot",
            "jdk.vm.ci.meta",
            "jdk.vm.ci.runtime",
    };

    private static volatile boolean done = false;

    private ModuleOpener() {}

    /**
     * Reflectively call {@code Module.implAddOpens(String, Module)} on any
     * module + package. Performs the same Unsafe-bypass of the
     * {@code setAccessible} check used by {@link #openJvmci()}, so callers
     * do NOT need {@code --add-opens java.base/java.lang=ALL-UNNAMED}.
     * <p>
     * Usage example — open {@code java.nio} so Oxidizium's cramming module
     * can read {@code Buffer.address} via reflection without a JVM flag:
     * <pre>
     * ModuleOpener.opensPackage("java.base", "java.nio");
     * </pre>
     * <p>
     * Idempotent — repeat calls are cheap.
     */
    public static void opensPackage(String moduleName, String packageName) {
        Module target = ModuleLayer.boot().findModule(moduleName)
                .orElseThrow(() -> new IllegalStateException("Module not found: " + moduleName));
        try {
            Method addOpens = Module.class.getDeclaredMethod(
                    "implAddOpens", String.class, Module.class);
            JavaInternals.setAccessible(addOpens);

            Module system   = ClassLoader.getSystemClassLoader().getUnnamedModule();
            Module platform = ClassLoader.getPlatformClassLoader().getUnnamedModule();
            Module here     = ModuleOpener.class.getModule();

            addOpens.invoke(target, packageName, system);
            if (platform != system) addOpens.invoke(target, packageName, platform);
            if (here != system && here != platform) {
                addOpens.invoke(target, packageName, here);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Failed to open " + moduleName + "/" + packageName + " reflectively. " +
                    "Fallback: pass --add-opens " + moduleName + "/" + packageName + "=ALL-UNNAMED " +
                    "on the launch line.", e);
        }
    }

    static synchronized void openJvmci() {
        if (done) return;

        Module jvmci = ModuleLayer.boot().findModule("jdk.internal.vm.ci")
                .orElseThrow(() -> new IllegalStateException(
                        "JVMCI module not found. Use -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI"));

        try {
            Method addExports = Module.class.getDeclaredMethod(
                    "implAddExports", String.class, Module.class);
            JavaInternals.setAccessible(addExports);   // Unsafe-based override bypass

            // Open to every unnamed module we can reach. Fabric's knot lives in its own
            // unnamed module; some users will load Nalim from the system/platform CL.
            Module system   = ClassLoader.getSystemClassLoader().getUnnamedModule();
            Module platform = ClassLoader.getPlatformClassLoader().getUnnamedModule();
            Module here     = ModuleOpener.class.getModule();

            for (String pkg : JVMCI_PACKAGES) {
                addExports.invoke(jvmci, pkg, system);
                if (platform != system) {
                    addExports.invoke(jvmci, pkg, platform);
                }
                if (here != system && here != platform) {
                    addExports.invoke(jvmci, pkg, here);
                }
            }
            done = true;
        } catch (NoSuchMethodException e) {
            // Method.implAddExports moved or renamed — bail out, the user must add the
            // five --add-exports flags manually (or use -javaagent:nalim.jar).
            throw new IllegalStateException(
                    "Could not locate java.lang.Module.implAddExports — " +
                    "either use -javaagent:nalim.jar, or add the five " +
                    "--add-exports=jdk.internal.vm.ci/jdk.vm.ci.*=ALL-UNNAMED flags.", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to open JVMCI packages reflectively", e);
        }
    }
}
