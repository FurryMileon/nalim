/*
 * Copyright (C) 2026
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package one.nalim;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

/** Cache the JDK lookup operation, never the contents of a loader's native libraries. */
final class NativeSymbols {
    private static final MethodHandle FIND = resolve();

    private NativeSymbols() {
    }

    private static MethodHandle resolve() {
        try {
            Method method;
            try {
                method = ClassLoader.class.getDeclaredMethod("findNative",
                        ClassLoader.class, Class.class, String.class, String.class);
            } catch (NoSuchMethodException oldJdk) {
                method = ClassLoader.class.getDeclaredMethod("findNative", ClassLoader.class, String.class);
            }
            JavaInternals.setAccessible(method);
            MethodHandle find = MethodHandles.lookup().unreflect(method).bindTo(Linker.class.getClassLoader());
            if (method.getParameterCount() == 4) {
                // Four-argument JDKs: bind the native-access caller and pass the symbol as both
                // the dlsym entry name and the Java method name used in diagnostics.
                return MethodHandles.permuteArguments(find.bindTo(Linker.class),
                        MethodType.methodType(long.class, String.class), 0, 0);
            }
            return find;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot resolve ClassLoader.findNative", e);
        }
    }

    static long find(String symbol) {
        try {
            return (long) FIND.invokeExact(symbol);
        } catch (Throwable e) {
            // As with Method.invoke, report a lookup failure rather than a miss.
            throw new IllegalStateException("ClassLoader.findNative failed for " + symbol, e);
        }
    }
}
