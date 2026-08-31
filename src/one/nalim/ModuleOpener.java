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
 * Exports {@code java.base/jdk.internal.misc} and the
 * {@code jdk.internal.vm.ci/jdk.vm.ci.*} packages to the calling unnamed
 * modules at runtime, without needing the Nalim Java agent or explicit
 * {@code --add-exports} flags. This keeps Nalim usable inside isolated class
 * loaders (for example, Fabric's Knot loader) with only JVMCI enabled.
 * <p>
 * The opener locates {@code Module.implAddExports(String, Module)}
 * reflectively. Ordinary reflective access is used when {@code java.lang} is
 * already open; the lazy {@link JavaInternals} compatibility bootstrap is used
 * otherwise. The exports target the platform, system, and Nalim unnamed
 * modules, mirroring {@link Agent#premain}'s official Instrumentation path.
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
     * Reflectively calls {@code Module.implAddOpens(String, Module)} for a
     * module and package. Uses the same ordinary-reflection-first compatibility
     * path as {@link #openJvmci()}, so callers do not need
     * {@code --add-opens java.base/java.lang=ALL-UNNAMED}.
     * <p>
     * Usage example — open {@code java.nio} before reading
     * {@code Buffer.address} reflectively without a JVM flag:
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
                        "JVMCI module not found. Add --add-modules=jdk.internal.vm.ci; "
                                + "JDK 21-24 also require "
                                + "-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI"));

        try {
            Method addExports = Module.class.getDeclaredMethod(
                    "implAddExports", String.class, Module.class);
            JavaInternals.setAccessible(addExports);   // ordinary reflection or lazy fallback

            // Open to every unnamed module we can reach. Fabric's knot lives in its own
            // unnamed module; some users will load Nalim from the system/platform CL.
            Module system   = ClassLoader.getSystemClassLoader().getUnnamedModule();
            Module platform = ClassLoader.getPlatformClassLoader().getUnnamedModule();
            Module here     = ModuleOpener.class.getModule();
            Module javaBase = Object.class.getModule();
            addExports.invoke(javaBase, "jdk.internal.misc", system);
            if (platform != system) {
                addExports.invoke(javaBase, "jdk.internal.misc", platform);
            }
            if (here != system && here != platform) {
                addExports.invoke(javaBase, "jdk.internal.misc", here);
            }

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
            // Module.implAddExports moved or renamed — require explicit exports
            // or the Java agent instead of continuing with partial access.
            throw new IllegalStateException(
                    "Could not locate java.lang.Module.implAddExports — use -javaagent:nalim.jar, " +
                    "or add --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED and the five " +
                    "jdk.internal.vm.ci exports.", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to open JDK internal packages reflectively", e);
        }
    }
}
