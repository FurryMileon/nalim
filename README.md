# Nalim v2

Nalim uses HotSpot's JVMCI to link Java methods to short C or Rust functions. It cuts call overhead when Java calls native code in a hot loop. On a Minecraft server, a mod such as RustMi has to use Nalim for it to affect tick time. Installing `nalim.jar` alone won't speed up the server.

**[Download nalim.jar 1.2.5](https://raw.githubusercontent.com/FurryMileon/nalim/84fb642e623218f05c2a04439d5153439e906cef/nalim.jar)** · [Binding and ABI reference for this build](https://github.com/FurryMileon/nalim/blob/84fb642e623218f05c2a04439d5153439e906cef/README.md)

- [Run RustMi with Nalim](#run-rustmi-with-nalim) to enable the backend and check which version the server loaded. No Java changes needed.
- [Call native code from Java](#call-native-code-from-java) to process an array in one call or let HotSpot inline a small integer function into your loop.
- [Reproduce the benchmarks](#benchmarks) to see which calls got faster and by how much.

## Run RustMi with Nalim

These instructions are for **Minecraft 26.3 with Fabric and [RustMi](https://github.com/DUzzL/RustMi) on Linux x86-64** (Intel or AMD). Start with HotSpot JDK 25 and its default G1 collector. On other platforms, let RustMi select its backend; it can use the Foreign Function & Memory API (FFM).

1. Stop the server. Keep `rustmi-*.jar` in `mods/`: it already bundles Nalim. If Fabric's mod list shows a version older than `1.2.5-patched-autoinline`, put [the v2 JAR](https://raw.githubusercontent.com/FurryMileon/nalim/84fb642e623218f05c2a04439d5153439e906cef/nalim.jar) in `mods/`. Replace any older standalone copy of Nalim. You don't need to unpack RustMi or add `-javaagent`.
2. Run this command from the server directory. If you use a hosting panel, put the JVM options **before `-jar`** and keep your existing memory settings. **On JDK 26**, also add `-XX:-UseCodeCacheFlushing` before `-jar`; otherwise RustMi won't select Nalim.

   ```sh
   java --add-modules=jdk.internal.vm.ci --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -jar fabric-server-launch.jar nogui
   ```

3. Look for **both** lines in `logs/latest.log`. The first tells you which Nalim JAR Fabric loaded. The second tells you whether RustMi is actually using it.

   ```text
   nalim 1.2.5-patched-autoinline
   RustMI native backend: Nalim (JVMCI)
   ```

You can also check the backend with `/rustmi stats`. If the log says `FFM (…)`, the reason is in parentheses. To diagnose a failure on a test server with the Linux and JDK setup above, add `-Drustmi.backend=nalim`. RustMi will report the error at startup instead of falling back to FFM. To switch back to FFM, remove the Nalim flags and run the command below. Use this on stock JDK 27 or a JDK 28 EA build without JVMCI as well.

```sh
java --enable-native-access=ALL-UNNAMED -Drustmi.backend=ffm -jar fabric-server-launch.jar nogui
```

**Measuring server performance.** Restore the same world snapshot before each run, replay the same workload, and give each server the same warm-up. Alternate the order: A/B, then B/A. To compare Nalim versions, change only the JAR and confirm both runs use `Nalim`. To compare Nalim with FFM, keep the JAR and change only the backend.

On a test copy, `/tick sprint 3000` reports the average time per tick for that run. On a live server, compare median and p95 **milliseconds per tick (MSPT)**. The p95 is the time within which 95% of ticks finish. Two servers can both report 20 TPS even if one needs 5 ms per tick and the other needs 45 ms.

## Call native code from Java

**For arrays, start by making fewer calls.** Pass a small array or a slice to one native function instead of calling a native `load` for each element. For example, [`nalimSumHeap(long[] p, int n)`](test/BenchMemAccess.java) calls [`mem_sum_longs(const int64_t* p, int32_t n)`](test/nalim_mem.c) without copying the array. Load the library and run `Linker.linkClass(...)` once during class initialization. Compare the result with a plain Java loop: Java can be faster when each element needs little work.

Check `p != null` and `0 <= n <= p.length` in Java before calling the function. The native code must not keep the array address after it returns, block, or call back into Java. Use JNI or FFM for long computations, I/O, or callbacks. Match the Java signature to the function's C ABI; in Rust, export the function with `extern "C"`.

**For a short arithmetic function called in a loop**, try `Linker.findIntrinsic` on Linux x86-64. Nalim inspects the compiled function and looks for an exact Java equivalent. The symbol name alone is not enough. It recognizes some functions that take one or two arguments, all `int` or all `long`, and return the same type. If there's a match, HotSpot can inline the Java equivalent into the loop. A plain `@Link` call does not switch to a `MethodHandle` automatically.

This example calls `raw_add` from [`test/nalim_bench.c`](test/nalim_bench.c). Clone v2, then save the class below as `Demo.java` in the `nalim` directory:

```sh
git clone --branch v2 https://github.com/FurryMileon/nalim.git
cd nalim
```

```java
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import one.nalim.Link;
import one.nalim.Linker;

public final class Demo {
    @Link(name = "raw_add")
    private static native int nativeAdd(int x, int y);

    private static final MethodHandle ADD;
    static {
        Linker.loadLibrary("nalim_bench");
        ADD = Linker.findIntrinsic("raw_add",
                MethodType.methodType(int.class, int.class, int.class)).orElse(null);
        if (ADD == null) Linker.linkClass(Demo.class);
    }

    static int add(int x, int y) throws Throwable {
        return ADD == null ? nativeAdd(x, y) : (int) ADD.invokeExact(x, y);
    }

    public static void main(String[] args) throws Throwable {
        System.out.println((ADD == null ? "@Link" : "intrinsic") + ": " + add(19, 23));
    }
}
```

From the repository root on **Linux x86-64 with JDK 25** and a C compiler, run:

```sh
cc -O2 -shared -fPIC test/nalim_bench.c -o libnalim_bench.so
javac -cp nalim.jar Demo.java
java --add-modules=jdk.internal.vm.ci --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Djava.library.path=. -cp nalim.jar:. Demo
```

`intrinsic: 42` means Nalim recognized the compiled function. `@Link: 42` means it used the native fallback, which is normal if your C compiler emitted different instructions. To test the fallback, add `-Dnalim.noinline=true` before `-cp` in the last command. Remove it before timing the fast path.

Look up the `MethodHandle` once, store it in a `static final` field, and call `invokeExact` with the exact primitive types. The function's machine code must stay unchanged while you use its Java equivalent. Both paths in this example require the v2 JAR because v1 has no `findIntrinsic` API.

## Benchmarks

[`BenchLeafCalls`](test/BenchLeafCalls.java) ran on Linux x86-64 with a Ryzen AI 9 365, OpenJDK 25.0.5.0.3, and G1. Each pair used two fresh JVMs pinned to the same core; the order alternated across eight pairs. The figures are medians across processes, in **nanoseconds per element of a warmed-up loop** (lower is better). On `main`, the benchmark's `MethodHandle` calls the linked native method because that version has no Java equivalent for the call.

| Operation in the loop | `main` (`d367a5e`) | v2 (`1.2.5`) |
|---|---:|---:|
| Add: plain `@Link` | 1.1150 | 1.1165 |
| Add: `static final` + `invokeExact` | 1.1195 | 0.0625 |
| Absolute value: plain `@Link` | 0.9022 | 0.9018 |
| Absolute value: `static final` + `invokeExact` | 0.9030 | 0.0635 |

With `invokeExact`, v2 processed **17.9× as many additions** and **14.2× as many absolute values** per second as `main`. Plain `@Link` took about the same time on both versions. These ratios measure what the JIT can do with the whole loop; they don't predict individual call latency or Minecraft TPS.

To reproduce the comparison on Linux x86-64, use a full v2 clone with Git history, JDK 25, `cc`, Python 3, and `taskset`. Run the following from the repository root. The script picks an available CPU by default; pass `--cpu` to choose one (this run used `--cpu 2`).

```sh
(
  set -e
  baseline=$(mktemp -d)
  trap 'rm -rf -- "$baseline"' EXIT
  git show d367a5e:nalim.jar > "$baseline/main.jar"
  python3 test/bench-link-paths.py "$baseline/main.jar" nalim.jar --workload leaves --pairs 8
)
```

Look for `raw_add.intrinsic=1` and `raw_abs.intrinsic=1` in the `after` output. The script checks the results against expected checksums. To measure ordinary linking paths too, replace `--workload leaves` with `--workload paths leaves`.

**RustMi call patterns.** In the [RustMi ABI benchmark](test/BenchRustMiAbi.java) on JDK 26, `spawncap` went from 1.369 to 1.199 ns per call between `main` and v2. `collision` barely changed (2.142 to 2.141 ns), and `distance` took slightly longer (2.084 to 2.097 ns).

**Minecraft tick time.** A test server running RustMi 1.2.1 and Nalim 1.2.5 on Java 26 matched vanilla on 8,278,065 collision checks with zero mismatches. That run checked correctness. In a separate, earlier tick-time comparison of Nalim 1.2.1 and 1.2.2, the newer JAR won two of four pairs. Those runs did not show a consistent MSPT improvement from swapping the JAR alone.

## Platform support and builds

- **Platforms:** Nalim supports Linux and macOS on x86-64 and AArch64, and Windows on x86-64. `amd64` includes Intel and AMD CPUs. Windows on Arm is unsupported. The [native ABI tests](.github/workflows/platforms.yml) run on all five combinations with JDK 21, 25, and 26. RustMi automatically selects Nalim on a narrower set of platforms.
- **Java:** Use HotSpot with `jdk.internal.vm.ci` (check `java --list-modules`). On JDK 21, add `-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI` to the example's JVM flags. On JDK 26, add `-XX:-UseCodeCacheFlushing`. On JDK 11 and 17, only the basic native call is tested, and only on Linux x86-64. Stock JDK 27 [removed JVMCI](https://bugs.openjdk.org/browse/JDK-8385142); the JDK 28 EA build we tested also lacks it. A launch flag cannot add a missing module.
- **Build:** Run `sh build.sh` from the repository root to produce `nalim.jar`. To test an existing JAR, run `bash test/check-platform.sh nalim.jar` with JDK 21, 25, or 26 and a C compiler (Git Bash and GCC on Windows). To build a runtime with the JVM options already set, use [`build-runtime.sh`](build-runtime.sh) or [`build-runtime.ps1`](build-runtime.ps1).

Based on [Andrei Pangin's Nalim](https://github.com/apangin/nalim). [GPL-2.0-or-later](LICENSE).
