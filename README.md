# nalim

Nalim is a library for linking Java methods to native functions using
[JVMCI](https://openjdk.org/jeps/243) (JVM compiler interface).

Unlike other Java frameworks for native library access, nalim does not
use JNI and therefore does not incur [JNI related overhead](https://stackoverflow.com/a/24747484/3448419).

When calling a native function with nalim
 - a thread does not switch from `in_Java` to `in_native` state and back;
 - no memory barrier is involved;
 - no JNI handles are created;
 - exception checks and safepoint checks are omitted;
 - native function can access primitive arrays directly in the heap.

As a result, native calls become faster comparing to JNI, especially when
a target function is short. In this sense, nalim is similar to
[JNI Critical Natives](https://stackoverflow.com/a/36309652/3448419),
but relies on a standard supported interface. JNI Critical Natives
have been [deprecated](https://bugs.openjdk.org/browse/JDK-8233343) in JDK 16
and [obsoleted](https://bugs.openjdk.org/browse/JDK-8258192) since JDK 18,
so nalim can serve as a replacement.

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
 - multi-prefix alignment NOPs and `pause`, so `-O2` loop padding
   does not break inlining.

The verified control-flow graph allows conditional branches in either
direction — **loops inline** — and unconditional forward/backward `jmp
rel8/rel32`, as long as every target lands on a verified instruction boundary
inside the window. Up to eight RIP-relative constants are relocated from
non-writable mappings into a local pool aligned up to 32 bytes (ymm literals
included; requirements above the JVM's `CodeEntryAlignment` are rejected).

Anything containing a call, indirect branch, EVEX (AVX-512) instruction,
`lock`-prefixed atomic, mutable RIP-relative operand or unknown encoding is
rejected and remains on the safe fallback path.
On AMD64, that fallback is normally a JVMCI-relocated five-byte `jmp rel32`.
Nalim retains the old 12-byte `movabs + jmp` sequence when the JVM reports that
the target is outside the code cache's signed 32-bit reach. Narrow-return
bridges use a JVMCI-relocated `call rel32` under the same reachability rule.

AMD64 `boolean`, `byte`, `short` and `char` returns are extended to the Java ABI
width before control returns to compiled Java. A small function with one terminal
`ret` stays auto-inlined and receives the extension in place. Other narrow-return
functions use a real-call bridge when every argument is register-resident; a
narrow-return signature requiring native stack arguments is rejected explicitly.
Non-AMD64 narrow native returns are not supported yet.

With the current Rustmi Linux library, 56 of 142 bindings were auto-inlined by
the previous forward-only decoder. The extended decoder matters most for
modern-tuned builds: on `bench_rng_rust` compiled with
`-C target-cpu=x86-64-v3`, acceptance goes from 3 of 14 (old decoder) to
10 of 14 — VEX scalar float and BMI2 integer encodings no longer knock a
function off the inline path. The two paths can be forced for
correctness/performance A/B testing with `-Dnalim.noinline=true` and
`-Dnalim.nodirect=true`; `-Dnalim.trace.inline=true` logs per-symbol decisions
with the bail-out opcode, and `-Dnalim.dump=<dir>` writes every installed
nmethod (post-relocation, exactly what the CPU executes) to
`<dir>/<Class>.<method>.bin` for `objdump -D -b binary -m i386:x86-64`.

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

### Running

#### 1. As an agent

```
java -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI \
     -javaagent:nalim.jar -cp <classpath> MainClass
```

This is the simplest way to add nalim to your application,
as the agent exports all required JDK internal packages for you.

The agent optionally accepts a list of classes whose native methods
will be automatically linked at startup:
```
-javaagent:nalim.jar=com.example.MyLib,com.example.OtherLib
```

#### 2. On the classpath

The current build opens the JVMCI packages before their classes are resolved, so
the classpath form normally needs only the two JVMCI flags as well:

```
java -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI \
     --enable-native-access=ALL-UNNAMED                 \
     -cp nalim.jar:app.jar MainClass
```

`--enable-native-access` suppresses the restricted-native-access warning on
recent JDKs. The reflective package opener reports the equivalent manual
`--add-exports`/`--add-opens` fallback if a future JVM blocks this mechanism.

On JDK 26, add `-XX:-UseCodeCacheFlushing` when a linked native method may stay
idle for many GC cycles and then be used again. JDK 26 permits idle native
nmethods to be cold-flushed, but it cannot recompile a body installed by Nalim.
Actively called methods are protected by the executable entry barrier.

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

The seven newly accepted forward-branch Rustmi leaves measure 1.033–1.463 ns/op
with Nalim versus 3.353–5.133 ns/op with Panama critical on JDK 25: a
2.51–3.90x advantage. A forced-fallback A/B run shows clear 14–56% gains for six
of those leaves and statistical parity for `clamped_map_float`.

Functions newly accepted by the loop/jmp/VEX/BMI decoder, measured inline vs
forced stub (`-Dnalim.noinline`) under identical conditions (JDK 25, same
host, best of 5 × ≥20M direct-loop iterations):

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

The same current jar was also compared with Membrane on JDK 17, the newest JDK
on which the local Membrane build can install its generated nmethods:

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

 - **Linux:** amd64 aarch64
 - **macOS:** amd64 aarch64
 - **Windows:** amd64

### Limitations

A native function called with nalim has certain limitations comparing to a regular
JNI function.

1. It must be `static`.
2. It does not have access to `JNIEnv` and therefore cannot call JNI functions,
   in particular, it cannot throw exceptions.
3. Only primitive types, primitive arrays and plain objects with primitive fields
   can be passed as arguments.
4. A function must return as soon as possible, since it blocks JVM from reaching 
   a safepoint.

Due to JVMCI limitation in HotSpot, nalim works only with Parallel, Serial and G1 GC,
and also with ZGC since JDK 21.
