#!/usr/bin/env python3
"""Compare two Nalim jars on Linux x86-64; use a JDK 25 toolchain."""
import argparse
import os
from pathlib import Path
from statistics import median
from subprocess import check_output, run
from tempfile import TemporaryDirectory

# Each workload runs in its own fresh process, even when several are selected.
workloads = {
    "paths": ("BenchLinkPaths", ("nalim_bench.c", "nalim_fx_asm.S")),
    "leaves": ("BenchLeafCalls", ("nalim_bench.c", "nalim_mem.c")),
    "code": ("BenchCodeBinding", ()),
    "symbols": ("BenchSymbolLookup", ("nalim_bench.c",)),
    "win-emit": ("one.nalim.BenchWinEmit", ()),
}

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("before", type=Path)
parser.add_argument("after", type=Path)
parser.add_argument("--cpu", type=int, default=min(os.sched_getaffinity(0)))
parser.add_argument("--workload", nargs="+", choices=workloads, default=["paths"])
parser.add_argument("--pairs", type=int, default=8)
args = parser.parse_args()
if args.pairs < 1:
    parser.error("--pairs must be positive")
before, after = (jar.resolve(strict=True) for jar in (args.before, args.after))
tests = Path(__file__).resolve().parent
jdk = Path(os.environ["JAVA_HOME"]) / "bin" if os.environ.get("JAVA_HOME") else Path("")

with TemporaryDirectory(prefix="nalim-bench-") as tmp:
    for source in sorted({s for key in args.workload for s in workloads[key][1]}):
        name = Path(source).stem
        run(["cc", "-O2", "-shared", "-fPIC", tests / source, "-o", f"{tmp}/lib{name}.so"], check=True)
    classes = [workloads[key][0] for key in args.workload]
    sources = [tests / (c.rsplit(".", 1)[-1] + ".java") for c in classes]
    run([jdk / "javac", "--release", "21", "-cp", after, "-d", tmp, *sources], check=True)
    cmd = ["taskset", "-c", str(args.cpu), jdk / "java", "-Xms256m", "-Xmx256m", "-XX:+UseG1GC", "-Xbatch",
           "-XX:ActiveProcessorCount=2", "--add-modules=jdk.internal.vm.ci", "--add-opens=java.base/java.lang=ALL-UNNAMED",
           "--enable-native-access=ALL-UNNAMED", "-Duser.language=en", "-Duser.country=US", f"-Djava.library.path={tmp}"]
    samples = {"before": [], "after": []}
    order = (("before", before), ("after", after))
    for pair in range(args.pairs):
        for label, jar in (order if pair % 2 == 0 else reversed(order)):
            sample = {}
            for cls in classes:
                raw = check_output([*cmd, "-cp", f"{jar}:{tmp}", cls], text=True)
                print(f"pair={pair + 1} {label} {cls}: {' '.join(raw.split())}", flush=True)
                sample.update({k: float(v) for k, v in (field.split("=") for field in raw.split())})
            samples[label].append(sample)

print("metric                         before        after   (fork medians)")
for name in samples["before"][0]:
    if any(name not in row for rows in samples.values() for row in rows):
        raise ValueError(f"metric missing from one or more runs: {name}")
    a, b = (median(row[name] for row in samples[label]) for label in ("before", "after"))
    print(f"{name:<26} {a:10.4f}   {b:10.4f}")
