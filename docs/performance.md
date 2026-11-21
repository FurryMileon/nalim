# Nalim performance: v1 and v2

## What changed for small integer functions?

[BenchLeafCalls](../test/BenchLeafCalls.java) runs the same Java bytecode against
[v1 (`d367a5e`)](https://github.com/FurryMileon/nalim/tree/d367a5e887652c7d050bd33f187cb2ee43edc8f0) and the
[checked-in v2 JAR (`84fb642`, version 1.2.5)](https://github.com/FurryMileon/nalim/blob/84fb642e623218f05c2a04439d5153439e906cef/nalim.jar).
The test ran on a Ryzen AI 9 365 with Linux x86-64, OpenJDK 25.0.5.0.3 and G1. Each value
is the median across eight fresh JVMs for that JAR, pinned to CPU 2. The runner
alternated which JAR ran first in each pair.

| Operation and call site | v1 (ns/element) | v2 (ns/element) |
| --- | ---: | ---: |
| `raw_add`, plain `@Link` | 1.1150 | 1.1165 |
| `raw_add`, `invokeExact` | 1.1195 | 0.0625 |
| `raw_abs`, plain `@Link` | 0.9022 | 0.9018 |
| `raw_abs`, `invokeExact` | 0.9030 | 0.0635 |

The plain `@Link` controls barely moved: addition took slightly longer and
absolute value slightly less time. With the optional Java handles, v2 processed
**17.9× as many additions and 14.2× as many absolute values per second** as v1.
These are throughput ratios for warmed-up loops, not latencies for isolated calls.

## Which call path should I use?

A direct call to an `@Link` native method still uses Nalim's native adapter.
To let the JIT optimize a recognized function together with its caller, load the
library and call `Linker.findIntrinsic(symbol, methodType)` once at setup. Cache
the returned `MethodHandle` in a `static final` field and use `invokeExact` with
the exact primitive argument and return types. The function's machine code must
remain unchanged while you use the handle.

Recognition depends on the compiled instructions, not just the symbol name. If
there is no match, use the linked native method. `BenchLeafCalls` obtains a
handle to that method as its fallback; this is also what its `invokeExact` rows
measure on v1, where `findIntrinsic` does not exist.

## Reproduce the comparison

Start in the repository root of a full v2 clone with both commits in its Git
history. On Linux x86-64, install a HotSpot JDK 25 with `javac` and JVMCI,
Python 3, `cc` and `taskset`. Set `JAVA_HOME` to that JDK or put its `bin` on
`PATH`. No Nalim build is needed. The [runner](../test/bench-link-paths.py)
compiles the two C libraries and compiles the Java harness once against v2 with
`javac --release 21`, all in a temporary directory.

```sh
(
  set -e
  tmp=$(mktemp -d)
  trap 'rm -rf -- "$tmp"' EXIT
  git show d367a5e887652c7d050bd33f187cb2ee43edc8f0:nalim.jar > "$tmp/v1.jar"
  git show 84fb642e623218f05c2a04439d5153439e906cef:nalim.jar > "$tmp/v2.jar"
  python3 test/bench-link-paths.py "$tmp/v1.jar" "$tmp/v2.jar" --workload leaves --pairs 8 --cpu 2
)
```

If CPU 2 is unavailable, omit `--cpu 2` to use the first allowed CPU. Look for
`raw_add.intrinsic=1` and `raw_abs.intrinsic=1` in the `after` output (`0`
means fallback). Each JVM warms up each loop, checks the result of each timed
sample, and reports the median of nine samples in nanoseconds per element.

## Earlier standalone comparisons

The [v1 Performance section](https://github.com/FurryMileon/nalim/blob/d367a5e887652c7d050bd33f187cb2ee43edc8f0/README.md#performance)
reports the original [JMH comparison](../example/one/nalim/bench/JniBench.java)
of JNI, Nalim and Panama for scalar addition and array maximum. It also reports
[long-array sums](../test/BenchMemAccess.java): for four elements the Java loop
was faster; for 64 and 512 elements one Nalim call for the whole array was
faster. Consider batching array work rather than crossing the boundary for
each element. Those comparisons use different workloads and hardware from the
v1–v2 loop measurements above; they do not measure an upgrade speedup.
