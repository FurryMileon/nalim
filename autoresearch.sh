#!/usr/bin/env bash
# Linux x86-64, HotSpot 25, cc and Python 3. Fixed workload, no downloads.
set -euo pipefail
export LC_ALL=C
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS

root=$(dirname -- "$(readlink -f -- "${BASH_SOURCE[0]}")")
if [[ $(uname -sm) != 'Linux x86_64' ]]; then
    echo 'This benchmark needs Linux x86-64.' >&2; exit 1
fi
jdk=${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk}
jdk=$(realpath -- "$jdk")
version=$("$jdk/bin/java" -version 2>&1)
if [[ ${version%%$'\n'*} != *'version "25.'* ]] || [[ $version != *'Server VM'* ]]; then
    printf 'This benchmark needs a HotSpot JDK 25; found:\n%s\n' "$version" >&2; exit 1
fi

work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
for source in src MANIFEST.MF fabric.mod.json; do ln -s -- "$root/$source" "$work/$source"; done
# Build from the current sources without replacing the repository's shipped jar.
(cd "$work"; PATH="$jdk/bin:$PATH" sh "$root/build.sh")
for file in nalim_bench.c nalim_fx_asm.S nalim_mem.c; do
    cc -O2 -shared -fPIC "$root/test/$file" -o "$work/lib${file%.*}.so"
done
cc -O2 -shared -fPIC "$root/test/rustmi_abi_fixtures.c" "$root/test/stack_args.c" -o "$work/librustmi_abi_fixtures.so"
"$jdk/bin/javac" --release 25 -cp "$work/nalim.jar" -d "$work" \
    "$root/test/BenchRustMiAbi.java" "$root/test/BenchLinkPaths.java" "$root/test/BenchLeafCalls.java"

python3 - "$jdk/bin/java" "$work" <<'PY'
import math
import os
import statistics
import subprocess
import sys

java, work = sys.argv[1:]
cpus = os.sched_getaffinity(0)
cpu = 2 if 2 in cpus else min(cpus)
command = ["taskset", "-c", str(cpu), java, "-Xms256m", "-Xmx256m", "-XX:+UseG1GC", "-Xbatch",
           "-XX:ActiveProcessorCount=2", "--add-modules=jdk.internal.vm.ci",
           "--add-opens=java.base/java.lang=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED",
           "-Duser.language=en", "-Duser.country=US", f"-Djava.library.path={work}",
           "-cp", f"{work}/nalim.jar:{work}"]

# Equal weight for the three RustMi shapes; stack and scalar leaves are separate guardrails.
primary_components = ("collide_abi_ns", "spawncap_abi_ns", "distance_abi_ns")
workloads = {
    "BenchRustMiAbi": {name: name for name in (*primary_components, "stack_abi_ns")},
    "BenchLinkPaths": {
        "plain.ns/call": "link_add_ns", "forward.ns/call": "link_forward_ns", "narrow.ns/call": "link_narrow_ns",
        "link.ns/method": "bind_ns_per_method", "link.heapBytes/method": "bind_heap_bytes_per_method",
        "firstClass.ms": "first_bind_ms",
    },
    "BenchLeafCalls": {
        "add.handle.ns/element": "intrinsic_add_ns", "abs.handle.ns/element": "intrinsic_abs_ns",
        "load.chase.ns/element": "heap_chase_ns",
    },
}
samples = {name: [] for metrics in workloads.values() for name in metrics.values()}
for fork in range(5):
    for benchmark, metrics in workloads.items():
        output = subprocess.check_output([*command, benchmark], text=True, timeout=90)
        values = {}
        for field in output.split():
            key, value = field.split("=", 1)
            if key in values:
                raise ValueError(f"duplicate {benchmark} sample {key}")
            values[key] = float(value)
        if benchmark == "BenchLeafCalls" and any(values.get(k) != 1 for k in ("raw_add.intrinsic", "raw_abs.intrinsic")):
            raise ValueError("Expected real verified intrinsics, not the native MethodHandle fallback")
        for key, name in metrics.items():
            value = values[key]
            if not math.isfinite(value) or value < 0 or (value == 0 and name != "bind_heap_bytes_per_method"):
                raise ValueError(f"invalid {benchmark} sample: {key}={value}")
            samples[name].append(value)
    shapes = " ".join(f"{name}={samples[name][-1]:.4f}" for name in primary_components)
    print(f"BENCH cpu={cpu} fork={fork + 1} {shapes}", file=sys.stderr, flush=True)

medians = {k: statistics.median(v) for k, v in samples.items()}
primary = statistics.geometric_mean(medians[k] for k in primary_components)
print(f"METRIC rustmi_abi_ns_per_call={primary:.4f}")
for name, value in medians.items():
    print(f"METRIC {name}={value:.4f}")
PY
