# nalim

Nalim is a library for linking Java methods to native functions using
[JVMCI](https://openjdk.org/jeps/243) (the JVM Compiler Interface). It follows
the original [apangin/nalim](https://github.com/apangin/nalim) design.

When Nalim calls a native function, the thread stays in `in_Java`. There is no
JNI transition fence, no JNI handle allocation, and no exception or safepoint
check inside the native function. HotSpot's compiled-method entry barrier still
runs when required. A native function can read or write a primitive array
directly in the Java heap.

This works best for short native functions, where the cost of entering JNI
would otherwise dominate the work. The tradeoff is similar to that of
[JNI Critical Natives](https://bugs.openjdk.org/browse/JDK-8233343): the
function must return promptly and must not call back into Java.

## What changed in v2?

[`main` is the preserved v1 branch](https://github.com/FurryMileon/nalim/tree/main)
(`1.2.0-patched-autoinline`). The default branch is `v2`
([download `nalim.jar`, `1.2.5-patched-autoinline`](https://raw.githubusercontent.com/FurryMileon/nalim/84fb642e623218f05c2a04439d5153439e906cef/nalim.jar)).
Existing `@Link`, `@Library`, `@FieldOffset`, and `@Code` declarations keep
their signatures. You don't have to rewrite them to upgrade.

| Area | v1 | v2 |
| --- | --- | --- |
| Short integer functions | `@Link` could copy a native body into its own compiled method. | On Linux x86-64, the new `Linker.findIntrinsic` can return a Java `MethodHandle` that HotSpot can inline **into the caller**. This is opt-in. |
| Ordinary `@Link` calls | Used an ABI adapter or a verified copy of the native body. | On Linux x86-64, selected scalar operations and single loads/stores can use Java's argument registers directly. Existing call sites pick this up automatically. |
| Native calling conventions | On x86-64 System V, the narrow-return bridge required register-only arguments. | The bridge also forwards spilled primitive arguments. v2 fixes mixed argument passing on Win64 and AArch64, and emits the entry barrier required by newer AArch64 HotSpot builds. |
| Runtime images | The builders expected a JDK with a `jmods/` directory. | They also work with linkable JDK images that have JVMCI but don't ship separate JMOD files. |

See [Upgrading from v1](docs/v2.md) for compatibility limits and a binding
example, or [Performance](docs/performance.md) for a reproducible comparison
of both JARs. Auto-inlining and direct tail jumps already existed in v1.

## Examples

### 1. Basic usage

On Linux or macOS, `getuid()` and `getgid()` are libc functions. `@Link`
uses the Java method name as the symbol name. Bind the methods once when the
class is initialized:

```java
import one.nalim.Link;
import one.nalim.Linker;

public class Libc {
    @Link public static native int getuid();
    @Link public static native int getgid();

    static {
        Linker.linkClass(Libc.class);
    }

    public static void main(String[] args) {
        System.out.println("My user id = " + getuid());
        System.out.println("My group id = " + getgid());
    }
}
```

Save this as `Libc.java` next to `nalim.jar`. With HotSpot JDK 25:

```sh
javac -cp nalim.jar Libc.java
java --add-modules=jdk.internal.vm.ci \
     --add-opens=java.base/java.lang=ALL-UNNAMED \
     --enable-native-access=ALL-UNNAMED \
     -cp nalim.jar:. Libc
```

The two numbers should match `id -u` and `id -g`. For other JDKs, see
[Running](#running).

### 2. Linking by a different name

The Java name doesn't have to match the exported C symbol. Here `allocate`
calls `malloc` and `release` calls `free`; on a 64-bit target, Java `long`
holds a pointer or a `size_t`.

```java
import one.nalim.Link;
import one.nalim.Linker;

public class Mem {
    @Link(name = "malloc") public static native long allocate(long size);
    @Link(name = "free")   public static native void release(long ptr);

    static {
        Linker.linkClass(Mem.class);
    }
}
```

### 3. Working with arrays

`@Library("crypto")` loads OpenSSL's `libcrypto` when the class is linked.
The C prototype is `unsigned char *SHA256(const unsigned char *, size_t, unsigned char *)`.
For a short input on a 64-bit system, pass both Java arrays directly. The
third argument points at the array where OpenSSL writes the 32-byte digest.

```java
import one.nalim.Library;
import one.nalim.Link;
import one.nalim.Linker;

@Library("crypto")
public class LibCrypto {
    @Link private static native long SHA256(byte[] data, long len, byte[] digest);

    static {
        Linker.linkClass(LibCrypto.class);
    }

    public static byte[] sha256(byte[] data) {
        byte[] digest = new byte[32];
        if (SHA256(data, data.length, digest) == 0) {
            throw new IllegalStateException("SHA256 failed");
        }
        return digest;
    }
}
```

Use a library on `java.library.path` and validate lengths in Java before the
call. Never retain a pointer into a Java array after the native method returns.

### 4. Accessing object fields

`@FieldOffset("sec")` passes the address of `sec` rather than the object's
header. This example uses Linux `clock_gettime` on a 64-bit ABI where
`struct timespec` consists of two consecutive 64-bit fields. Your Java field
layout must match the native layout; Nalim does not marshal a struct.

```java
import one.nalim.FieldOffset;
import one.nalim.Link;
import one.nalim.Linker;

public class Time {
    public long sec;
    public long nsec;

    @Link private static native int clock_gettime(int clk_id, @FieldOffset("sec") Time time);

    static {
        Linker.linkClass(Time.class);
    }

    public static Time current() {
        Time time = new Time();
        if (clock_gettime(0, time) != 0) {  // CLOCK_REALTIME on Linux
            throw new IllegalStateException("clock_gettime failed");
        }
        return time;
    }
}
```

### 5. Inlining raw machine code

`@Code` installs the supplied bytes as the body of a Java method. This
x86-64 example reads the timestamp counter and combines `EDX:EAX` into a
64-bit return value. The same bytes are **not** an AArch64 implementation.

```java
import one.nalim.Code;
import one.nalim.Linker;

public class Cpu {
    // rdtsc; shl $32, %rdx; or %rdx, %rax; ret
    @Code("0f31 48c1e220 4809d0 c3")
    public static native long rdtsc();

    static {
        Linker.linkClass(Cpu.class);
    }
}
```

### 6. Auto-inline and direct tail calls on x86-64

For eligible `@Link` calls, Nalim can verify a small native body (up to 128
bytes) and copy it into the installed method. Unrecognized instructions,
indirect branches, or mutable globals leave the function on the native call
path. When the ABI permits a tail jump, the linker can use a relative jump
if the address is in range. None of this requires changing the Java declaration.

`-Dnalim.trace.inline=true` shows binding decisions. Use `-Dnalim.noinline=true`
or `-Dnalim.nodirect=true` for A/B tests, not as speed settings. Set
`@Link(naked = true)` only for a function written for HotSpot's Java calling
convention; it skips the normal conversion to the C ABI.

## Building

Use the checked-in [`nalim.jar`](nalim.jar), or build it with a JVMCI-capable
JDK such as JDK 25. `./build.sh` needs only the JDK and a shell and writes
`nalim.jar` in the repository root. `gradle clean jar` writes `build/libs/nalim.jar`.
Both target Java 11 bytecode.

## Running

Nalim needs a HotSpot JDK that contains `jdk.internal.vm.ci` (check
`java --list-modules`). The [basic example](#1-basic-usage) includes the
classpath command for JDK 25. Explicit JVMCI `--add-exports` are not needed.
On Windows, use `;` instead of `:` between classpath entries.

| Stock JDK | What to change in the JDK 25 command |
| --- | --- |
| 21 through 24 | Add `-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI` before `--add-modules`. |
| 25 | No changes. |
| 26 | Add `-XX:-UseCodeCacheFlushing`. Otherwise HotSpot can discard an idle native binding that it cannot recompile. |
| 27 | [OpenJDK removed JVMCI](https://bugs.openjdk.org/browse/JDK-8385142). Use a JDK with the module. The tested JDK 28 EA build also lacks it. |

**Java agent.** As an alternative to `--add-opens`, pass `-javaagent:nalim.jar`
before `-cp`. Keep `--add-modules=jdk.internal.vm.ci` and the options for your
JDK. If a binding class does not call `Linker.linkClass` itself, you can pass
its name at startup: `-javaagent:nalim.jar=com.example.MyLib,com.example.OtherLib`.

**Flag-free runtime image.** Set `JAVA_HOME` to a JVMCI-capable JDK 21 or
newer. Run [`./build-runtime.sh`](build-runtime.sh) on Linux or macOS. In
Windows PowerShell, run [`build-runtime.ps1`](build-runtime.ps1) as `.\build-runtime.ps1`.
Both builders write a `nalim-runtime` directory with the JVM flags already set
and refuse to overwrite an existing directory. For the `Libc` example on Linux or macOS:

```sh
./nalim-runtime/bin/java -cp nalim.jar:. Libc
```

Build each image on the OS and architecture where it will run. Keep its
`legal/` directory if you redistribute it.

## Performance

For arrays, compare a plain Java loop with one native call for the whole
batch before trying a native call per element. For a small integer function
in a hot loop, Linux x86-64 callers can try `Linker.findIntrinsic` once at
setup and cache the exact handle. In a JDK 25 test on Linux x86-64, the
`raw_add` loop processed 17.9 times as many elements per second through this
handle as through v1's native fallback. Plain `@Link` addition took about the
same time on both JARs. See the [results and reproduction command](docs/performance.md).

## Supported platforms

- **Linux:** x86-64 and AArch64
- **macOS:** x86-64 and AArch64
- **Windows:** x86-64

`amd64` includes Intel and AMD CPUs. Windows on Arm and RISC-V are unsupported.
The [platform workflow](.github/workflows/platforms.yml) tests the shipped JAR
and a source build on all five targets with JDK 21, 25, and 26. JDK 11 and 17
have a basic native-call smoke test on Linux x86-64. To test your JAR on a
supported JDK 21/25/26 target, run `bash test/check-platform.sh nalim.jar`
with a C compiler (Git Bash and GCC on Windows).

## Limitations

1. Bind `static native` methods to a fixed C prototype. Match its argument widths
   and return type. For a variadic C function, write a fixed-signature wrapper.
2. The target cannot use `JNIEnv`, throw a Java exception, or call back into Java.
3. Pass primitive values, primitive arrays, or the address of a primitive field.
   Check references and buffer bounds in Java; native code has no such checks.
4. Return promptly. A blocking operation or a long-running computation can hold
   up a JVM safepoint. Use JNI or FFM for work that may block.
5. GC support depends on the HotSpot/JVMCI build. The platform tests exercise
   G1 and, where available, ZGC. Older supported HotSpot releases also work with
   Serial and Parallel.

[License: GPL-2.0-or-later](LICENSE).
