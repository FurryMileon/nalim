# nalim

Nalim is a library for linking Java methods to native functions using
[JVMCI](https://openjdk.org/jeps/243) (JVM compiler interface).

Unlike other Java native-access frameworks, Nalim does not use JNI and avoids
its transition overhead.

When Nalim calls a native function:

- the thread does not switch from `in_Java` to `in_native` and back;
- no memory barrier is involved;
- no JNI handles are created;
- exception and safepoint checks are omitted;
- primitive arrays can be accessed directly in the Java heap.

This matters most for short native functions, where transition cost would
otherwise dominate the call. Nalim is similar in spirit to
[JNI Critical Natives](https://stackoverflow.com/a/36309652/3448419), which
were [deprecated](https://bugs.openjdk.org/browse/JDK-8233343) in JDK 16 and
[obsoleted](https://bugs.openjdk.org/browse/JDK-8258192) in JDK 18.

### Maintained branch

This repository follows the original [apangin/nalim](https://github.com/apangin/nalim)
design and keeps it usable on current HotSpot releases. The main additions are:

- executable nmethod entry barriers for JDK 21 and newer;
- conservative x86-64 auto-inlining, direct tail calls and narrow-return bridges;
- System V and Windows x64 calling-convention fixes;
- runtime module opening, so normal use does not need a long list of
  `--add-exports` options;
- version-aware runtime-image builders for Linux, macOS and Windows.

The jar also contains Fabric metadata and can be shipped as a library mod. It
does not depend on Fabric API.

### Examples

#### 1. Basic usage

```java
public class Libc {

    @Link
    public static native int getuid();

    @Link
    public static native int getgid();

    static {
        Linker.linkClass(Libc.class);
    }
}
```
```
System.out.println("My user id = " + Libc.getuid());
```

#### 2. Linking by a different name 

```java
public class Mem {

   @Link(name = "malloc")
   public static native long allocate(long size);

   @Link(name = "free")
   public static native void release(long ptr);

   static {
      Linker.linkClass(Mem.class);
   }
}
```

#### 3. Working with arrays

```java
@Library("crypto")
public class LibCrypto {

    public static byte[] sha256(byte[] data) {
        byte[] digest = new byte[32];
        SHA256(data, data.length, digest);
        return digest;
    }

    @Link
    private static native void SHA256(byte[] data, int len, byte[] digest);
}
```

#### 4. Accessing object fields

```java
public class Time {
    public long sec;
    public long nsec;

    public static Time current() {
        Time time = new Time();
        clock_gettime(0, time);
        return time;
    }

    @Link
    private static native void clock_gettime(int clk_id, @FieldOffset("sec") Time time);

    static {
        Linker.linkClass(Time.class);
    }
}
```

#### 5. Inlining raw machine code

```java
public class Cpu {

    // rdtsc
    // shl    $0x20,%rdx
    // or     %rdx,%rax
    // ret
    @Code("0f31 48c1e220 4809d0 c3")
    public static native long rdtsc();

    static {
        Linker.linkClass(Cpu.class);
    }
}
```

#### 6. Auto-inline and direct tail calls on x86-64

Every `@Link` target is conservatively inspected before Nalim emits a call stub.
For a function of up to 128 bytes, Nalim embeds the body **directly into the
nmethod** after the Java-to-native register shuffle. The decoder accepts an
explicit whitelist of encodings:

 - integer ALU/mov/test/imul/div, shifts and rotates, movzx/movsx/movsxd,
   cmov/setcc, push/pop, inc/dec, bswap, shld/shrd, bsf/bsr,
   popcnt/lzcnt/tzcnt, 16-bit (66-prefixed) forms, `rep movs`/`rep stos`;
 - scalar and packed SSE/SSE2 (including the packed-integer core),
   SSSE3/SSE4.1 essentials (`pshufb`, `pmovsx/zx`, `pmulld`, `roundss/sd`);
 - the VEX-encoded AVX/AVX2 counterparts plus FMA, BMI1/BMI2
   (`andn/blsr/blsi/blsmsk/bzhi/pdep/pext/mulx/shlx/sarx/shrx/rorx`)
   and `vzeroupper` — libraries built with `-march=x86-64-v3` /
   `-C target-cpu=x86-64-v3` stay on the inline path;
 - multi-prefix alignment NOPs, `pause`, and Intel CET `ENDBR64` / `ENDBR32`
   landing pads, so compiler-generated entry and loop padding stays inlineable.

The verified control-flow graph allows conditional branches in either
direction — **loops inline** — and unconditional forward/backward `jmp
rel8/rel32`, as long as every target lands on a verified instruction boundary
inside the window. Up to eight RIP-relative constants are relocated from
non-writable mappings into a local pool aligned up to 32 bytes (ymm literals
included; requirements above the JVM's `CodeEntryAlignment` cannot use the pool).

On Linux x86-64, an exported symbol can also be a direct jump to the actual
implementation. Nalim follows up to four out-of-window entry jumps (`jmp rel8`
or `jmp rel32`, optionally after `ENDBR64`) inside the **same read-only executable
mapping**, then verifies the destination as usual. Speculative target and
cross-page reads go through procfs during binding, not at call time. Indirect
dispatch slots are never followed; if verification or the read fails, the
adapter calls the original symbol. The native text must stay unchanged while linked.

A call, indirect branch, EVEX (AVX-512) instruction, `lock`-prefixed atomic
or unknown encoding stays on the fallback path. RIP-relative loads that
cannot use the literal pool, and RIP-relative `lea`, can retain their original
address if it is within rel32 reach; `-Dnalim.norebase=true` disables this.
On AMD64, that fallback is normally a JVMCI-relocated five-byte `jmp rel32`.
Nalim retains the old 12-byte `movabs + jmp` sequence when the JVM reports that
the target is outside the code cache's signed 32-bit reach. Narrow-return
bridges use a JVMCI-relocated `call rel32` under the same reachability rule.

AMD64 `boolean`, `byte`, `short` and `char` returns are extended to the Java ABI
width before control returns to compiled Java. For small verified functions
with multiple exits, Nalim inserts that extension at every reachable `ret` /
`rep ret` and adjusts the internal branches and RIP operands. If a short
branch would no longer fit, the original call bridge is used instead.
On System V AMD64 that bridge requires register-resident arguments.
Non-AMD64 narrow native returns are not supported yet.

On Linux AMD64, a small set of complete integer functions and single `int`/`long`
loads or stores can run directly in HotSpot's argument registers. Ordinary
`@Link` users get this automatically. For a load/store through an array or
`@FieldOffset`, the CPU instruction combines the JVM-reported base offset and
the native displacement. This preserves the existing native pointer semantics;
it does not add Java null or bounds checks.

For hot Java loops calling pure integer leaves, `Linker.findIntrinsic` offers a
different path: a Java `MethodHandle` that C2 can inline into the **caller**.
It checks the supplied `MethodType` and the instructions through `ret` in
readable, non-writable executable text; it does not infer behavior from the
symbol name. A Linux runtime that denies the required procfs reads gets
`Optional.empty()`. Bind once, after loading the library:

```java
final class Maths {
    @Link(name = "raw_add") private static native int nativeAdd(int x, int y);
    private static final MethodHandle ADD;

    static {
        Linker.loadLibrary("mathlib"); // exports int raw_add(int, int)
        ADD = Linker.findIntrinsic("raw_add",
                MethodType.methodType(int.class, int.class, int.class)).orElse(null);
        if (ADD == null) Linker.linkClass(Maths.class);
    }

    static int add(int x, int y) throws Throwable {
        return ADD == null ? nativeAdd(x, y) : (int) ADD.invokeExact(x, y);
    }
}
```

The supported shapes are identity, negate, complement, absolute value, add,
subtract, multiply, and bitwise AND/OR/XOR on homogeneous `int` or `long`
arguments. A different compiler encoding, an entry thunk, a body crossing a
4 KiB page boundary, another platform, or `-Dnalim.noinline=true` can produce
`Optional.empty()`. Use the actual C ABI type and keep the library loaded and
its text unchanged. `findIntrinsic` neither installs a native method nor
silently substitutes a slower handle.

Inlining decisions can be inspected with `-Dnalim.trace.inline=true`.
`-Dnalim.noinline=true` and `-Dnalim.nodirect=true` force the fallback paths
for correctness and performance comparisons. `-Dnalim.dump=<dir>` writes each
installed nmethod after relocation to `<dir>/<Class>.<method>.bin`, ready for
inspection with `objdump -D -b binary -m i386:x86-64`.

On AMD64 with JDK 21+, the installed nmethod begins with HotSpot's executable
entry barrier. Its exact 32-byte frame and runtime call live on an out-of-line
slow path; the steady-state path executes only the barrier compare and a normally
not-taken branch before reaching the Nalim body.

Rust-side helper (writes args directly in HotSpot's Java calling convention —
rsi, rdx, rcx, r8, r9 for integer args):

```rust
#[unsafe(naked)]
#[no_mangle]
pub extern "sysv64" fn java_add() -> ! {
    std::arch::naked_asm!("lea (%rsi,%rdx,1),%eax", "ret",
                          options(att_syntax));
}
```

Java side:

```java
@Link(name = "java_add", naked = true)
public static native int add(int a, int b);
```

`naked = true` is still useful for a function deliberately authored in HotSpot's
Java register convention: it suppresses the register shuffle. It is no longer a
requirement for auto-inline; ordinary System V leaf functions are handled too.

### Building

The Gradle build uses the JDK running Gradle and targets Java 11 bytecode:

```bash
gradle clean jar
```

For a small dependency-free build:

```bash
./build.sh
```

Gradle writes `build/libs/nalim.jar`; `build.sh` refreshes the root
`nalim.jar`. Both jars contain `Premain-Class` and Fabric metadata.

### Running

Nalim uses `jdk.internal.vm.ci`. That module must be resolved before application
classes start loading; a stock JDK 25 or 26 launch without
`--add-modules=jdk.internal.vm.ci` fails with
`NoClassDefFoundError: jdk/vm/ci/code/CompiledCode`.

#### Flag-free runtime image

The supplied `jlink` builders bake the version-specific JVM options into a
separate runtime:

```bash
JAVA_HOME=/path/to/jdk-25 ./build-runtime.sh
./nalim-runtime/bin/java -cp nalim.jar:app.jar MainClass
```

Windows:

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-25"
.\build-runtime.ps1
.\nalim-runtime\bin\java.exe -cp "nalim.jar;app.jar" MainClass
```

The builders support JDK 21 and newer and refuse to overwrite an existing
output directory. Pass a path as the first argument to use a name other than
`nalim-runtime`.

The image contains all modules from the selected JDK and bakes these startup
defaults:

| JDK | Options embedded in the image |
| --- | --- |
| 21–24 | `-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI`, JVMCI module, module open, native access |
| 25 | JVMCI module, module open, native access |
| 26+ | Same as 25, plus `-XX:-UseCodeCacheFlushing` |

Runtime images are tied to the JDK vendor/version, OS and CPU architecture used
to build them. Keep the generated `legal/` directory when redistributing one.
To leave an existing service command unchanged, point `PATH` at the image:

```bash
export PATH=/opt/nalim-runtime/bin:$PATH
java -cp nalim.jar:app.jar MainClass
```

#### Stock JDK

The minimum JVMCI options are:

| JDK | Required JVM options |
| --- | --- |
| 21–24 | `-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI --add-modules=jdk.internal.vm.ci` |
| 25 | `--add-modules=jdk.internal.vm.ci` |
| 26+ | `--add-modules=jdk.internal.vm.ci`; add `-XX:-UseCodeCacheFlushing` for long-running processes |

`--enable-native-access=ALL-UNNAMED` is recommended on current JDKs. Without it,
library loading still works today but emits a restricted-access warning, and a
future JDK may deny the operation by default.

On JDK 26, cold code-cache flushing can discard an idle nmethod installed by
Nalim. Hot methods are protected by the entry barrier, but an externally
installed body cannot be recompiled after it has been flushed. Long-running
servers should therefore use `-XX:-UseCodeCacheFlushing`; the runtime builder
adds it automatically.

Using the Java agent:

```bash
java --add-modules=jdk.internal.vm.ci \
     --enable-native-access=ALL-UNNAMED \
     -javaagent:nalim.jar \
     -cp <classpath> MainClass
```

The agent may also link classes at startup:

```bash
-javaagent:nalim.jar=com.example.MyLib,com.example.OtherLib
```

Using Nalim directly from the classpath:

```bash
java --add-modules=jdk.internal.vm.ci \
     --add-opens=java.base/java.lang=ALL-UNNAMED \
     --enable-native-access=ALL-UNNAMED \
     -cp nalim.jar:app.jar MainClass
```

The classpath bootstrap opens the required JVMCI exports itself.
`--add-opens=java.base/java.lang=ALL-UNNAMED` is optional on current JDKs, but
it avoids the compatibility fallback through terminally deprecated
`sun.misc.Unsafe`. Explicit JVMCI `--add-exports` options are not required.

### Performance

Current JMH results on the local Ryzen 9 7950X3D host (Linux x86-64, G1, three
forks, average ns/op):

| Binding | Nalim JDK 25 | Panama critical JDK 25 | Nalim JDK 26 | Panama critical JDK 26 |
| --- | ---: | ---: | ---: | ---: |
| `abs(int)` | 1.075 | 3.280 | 1.037 | 3.762 |
| `add(int,int)` | 1.111 | 3.666 | 1.066 | 4.018 |
| `noop()` | 1.028 | 3.645 | 1.086 | 3.246 |

That is a 3.05–3.54x advantage over Panama critical on JDK 25 and a
2.99–3.77x advantage on JDK 26 for these minimal-call overhead tests.

Auto-inlining helps most when a short function has a serial dependency chain
that cannot hide the fallback jump. Representative bundled fixtures, measured
inline versus the forced stub path on JDK 25:

| Function | inline | stub (direct jmp) | gain |
| --- | ---: | ---: | ---: |
| `raw_sum_to(4)` — C loop | 1.635 | 1.995 | 18% |
| `raw_sum_to(16)` | 3.965 | 4.349 | 9% |
| `raw_sum_to(64)` | 13.965 | 14.323 | 2.5% |
| `fx_floor_v3` — `vroundsd` | 1.077 | 1.222 | 12% |
| `fx_lerp_v3` — VEX float | 1.306 | 1.364 | 4% |

Straight-line integer mixers (`splitmix`) show ≤2% — the tail jump overlaps
their multiply chain, so widening inline coverage matters most for short,
low-latency bodies.

`test/bench-link-paths.py` compares two jars using the same native libraries
and Java call sites, alternating their order over eight JVM pairs on one CPU:

```sh
JAVA_HOME=/path/to/jdk-25 python3 test/bench-link-paths.py /path/to/baseline.jar ./nalim.jar --cpu 2
# Include data-dependent integer loops, dependent heap loads, @Code binding,
# native symbol hits/misses and standalone Win64 frame generation:
JAVA_HOME=/path/to/jdk-25 python3 test/bench-link-paths.py /path/to/baseline.jar ./nalim.jar \
    --cpu 2 --pairs 8 --workload leaves paths code win-emit symbols
```

Native-call loops check their checksums; the `@Code` workload invokes each
installed body outside the timed interval. The Win64 workload consumes a CRC
of the generated code and measures emission, not execution on Windows. The
first-class timer starts **after** JVMCI initialization and library loading.
The warmed linking tests bind fresh classes, excluding class definition;
allocations are Java heap bytes on the linking thread, not code-cache space.

Linux x86-64, Ryzen AI 9 365, OpenJDK 25.0.5.0.3, G1; median of eight JVMs
per jar. The baseline already used **direct** jumps/calls for both wrappers.

| Measurement | `d367a5e` | First optimization pass |
| --- | ---: | ---: |
| Already-inlined `raw_add(i, 17)` (ns/call, control) | 2.519 | 2.517 |
| Forwarding entry with a RIP-relative literal (ns/call) | 2.529 | 2.014 |
| Branched `byte` classifier with two returns (ns/call) | 4.546 | 2.646 |
| First three-method class (ms) | 81.15 | 73.62 |
| Subsequent fresh classes (µs/method) | 16.65 | 15.38 |
| Linking-thread heap allocation (bytes/method) | 5,806 | 2,886 |

The next comparison uses the **first-pass jar** (`784784c9`), not `d367a5e`,
as the baseline. In `BenchLeafCalls`, the same `invokeExact` call sites use
handles to the linked native methods before, and verified Java equivalents
after. Each loop consumes changing integers and checks its sum. Nanoseconds
per element measure loop throughput, not the latency of an isolated FFI
call. Symbol lookup and intrinsic setup are outside these loops.

| Measurement | First-pass jar | Benchmarked v2 jar (`edbeb0ee`) |
| --- | ---: | ---: |
| `raw_add` via static-final handle (ns/element) | 2.5287 | 0.1515 |
| `raw_abs` via static-final handle (ns/element) | 2.0216 | 0.1535 |
| Ordinary `@Link raw_add` (ns/element) | 2.5257 | 2.5317 |
| Ordinary `@Link raw_abs` (ns/element) | 2.0250 | 2.0225 |
| Dependent `long[]` loads via `@Link` (ns/element) | 5.0596 | 4.5495 |
| First three-method `@Link` class (ms) | 73.38 | 61.36 |
| Subsequent fresh three-method `@Link` classes (µs/method) | 15.21 | 15.70 |
| `@Code` binding (µs/method) | 22.34 | 20.61 |
| `@Code` binding (heap bytes/method) | 4,105 | 2,743 |
| Win64 adapter emission (ns/frame) | 123.06 | 66.32 |
| Win64 adapter emission (heap bytes/frame) | 369 | 0 |

Both handle targets were reported as `inline (hot)` in the JDK 21 and 25 JIT
logs. The large arithmetic gains are not present at ordinary native call
sites. Standalone symbol lookup also showed no allocation reduction: zero
heap bytes on both jars, about 143 vs 142 ns for a hit and 1.270 vs 1.346 µs
for a miss. Win64 frame execution was checked separately with GCC `ms_abi`
callees on Linux, including shadow-space writes and mixed register/stack
arguments; no Windows JVM timing is implied.

Memory-path reference numbers from `BenchMemAccess` (sum of *n* longs, one
Nalim call into an AVX2-built kernel vs a C2-compiled Java loop vs Panama
critical with the same kernel):

| n | Java loop | Nalim 1 call | Panama critical |
| ---: | ---: | ---: | ---: |
| 4 | 1.43 | 1.81 | 5.09 |
| 16 | 1.99 | 2.41 | 5.84 |
| 64 | 7.99 | 4.73 | 7.46 |
| 512 | 93.4 | 28.0 | 30.6 |

Zero-copy heap-array passing costs the same as raw pointers (`lea` only). For
single loads/stores no FFI can compete with JIT intrinsics: `Unsafe.getLong`
0.20 ns, bounds-checked `MemorySegment.get` 0.28 ns, one Nalim call 1.19 ns,
Panama critical 4.06 ns — keep per-element access in Java and ship batches to
native kernels.

The per-call cost of the executable JDK 21+ entry barrier was measured at
~0.01 ns on Zen 4 (`-Dnalim.unsafe.legacybarrier=true` restores the legacy
never-executed form for measurement; do not use it on JDK 26+ — it forfeits
cold-flush protection for ~1%). `-Dnalim.legacypad=true` likewise restores the
old 1-byte-NOP prologue padding; the coalesced wide-NOP default measured
identical on Zen 4 and simply spends fewer decode slots.

The same current jar was also compared with
[Membrane](https://github.com/RelativityMC/MembraneFFI) on JDK 17, the newest
JDK on which the local Membrane build can install its generated nmethods:

| Binding | Nalim | Membrane | Nalim advantage |
| --- | ---: | ---: | ---: |
| `abs(int)` | 0.786 | 0.982 | 1.25x |
| `add(int,int)` | 0.979 | 1.178 | 1.20x |
| `noop()` | 0.978 | 1.172 | 1.20x |

JMH benchmark for comparing regular JNI calls with nalim calls is available
[here](https://github.com/apangin/nalim/blob/master/example/one/nalim/bench). 

The following results were obtained on Intel Core i7-1280P CPU with JDK 19.0.1.

#### Simple native method

```
static native int add(int a, int b);
```

```
Benchmark            Mode  Cnt  Score   Error  Units
JniBench.add_jni     avgt   10  6,718 ± 0,298  ns/op
JniBench.add_nalim   avgt   10  0,821 ± 0,032  ns/op
JniBench.add_panama  avgt   10  6,673 ± 0,307  ns/op
```

#### Array processing

```
static native long max(long[] array, int length);
```

```
Benchmark           (length)  Mode  Cnt    Score   Error  Units
JniBench.max_jni          10  avgt   10   24,642 ± 0,741  ns/op
JniBench.max_jni         100  avgt   10   54,626 ± 1,843  ns/op
JniBench.max_jni        1000  avgt   10  433,813 ± 0,864  ns/op
JniBench.max_nalim        10  avgt   10    3,540 ± 0,218  ns/op
JniBench.max_nalim       100  avgt   10   37,211 ± 0,308  ns/op
JniBench.max_nalim      1000  avgt   10  418,057 ± 0,529  ns/op
```

### Supported platforms

- **Linux:** amd64, aarch64
- **macOS:** amd64, aarch64
- **Windows:** amd64

### Limitations

A native function linked with Nalim has several constraints compared with a
regular JNI function:

1. It must be `static`.
2. It cannot access `JNIEnv`, call JNI functions or throw Java exceptions.
3. Arguments are limited to primitive values, primitive arrays and plain
   objects containing primitive fields.
4. It must return quickly because the call prevents the JVM from reaching a
   safepoint.

Due to JVMCI limitations in HotSpot, Nalim works with Parallel, Serial and G1,
and with ZGC on JDK 21 and newer.
