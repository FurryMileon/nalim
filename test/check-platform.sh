#!/usr/bin/env bash
# bash test/check-platform.sh [path/to/nalim.jar]
# With no argument, build the current sources without modifying the shipped jar.
set -euo pipefail
if (( $# > 1 )); then echo "usage: $0 [nalim.jar]" >&2; exit 2; fi
root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT

classpath='nalim.jar:.'
case $(uname -s) in
    Linux)  library=libplatform_abi.so;    shared=(-shared -fPIC); compiler=${CC:-cc} ;;
    Darwin) library=libplatform_abi.dylib; shared=(-dynamiclib -fPIC); compiler=${CC:-cc} ;;
    MINGW*|MSYS*|CYGWIN*)
        library=platform_abi.dll; shared=(-shared); compiler=${CC:-gcc}
        classpath='nalim.jar;.'
        if [[ -n ${JAVA_HOME:-} ]]; then JAVA_HOME=$(cygpath -au "$JAVA_HOME"); fi ;;
    *) echo "Unsupported test host: $(uname -s)" >&2; exit 1 ;;
esac
if [[ -n ${JAVA_HOME:-} ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
feature=$(javac -version | sed -n 's/^javac \([0-9][0-9]*\).*/\1/p')
if [[ -z $feature || $feature -lt 21 ]]; then echo 'The platform test requires JDK 21+.' >&2; exit 1; fi

if (( $# )); then
    cp -- "$1" "$work/nalim.jar"
else
    cp -R "$root/src" "$root/MANIFEST.MF" "$root/fabric.mod.json" "$work/"
    (cd "$work" && sh "$root/build.sh")
fi
cp "$root/test/"{platform_abi.c,PlatformAbiTest.java,PlatformSelectionTest.java} "$work/"
cd "$work"
"$compiler" -std=c11 -O2 "${shared[@]}" platform_abi.c -o "$library"
javac -cp nalim.jar -d . PlatformAbiTest.java PlatformSelectionTest.java

vm=(--add-modules=jdk.internal.vm.ci --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED)
if (( feature < 25 )); then vm+=(-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI); fi
if (( feature >= 26 )); then vm+=(-XX:-UseCodeCacheFlushing); fi
java "${vm[@]}" -cp "$classpath" one.nalim.PlatformSelectionTest
vm+=(-Xmx128m -Xbatch -XX:-TieredCompilation -XX:CompileThreshold=1000
     -XX:CompileCommand=dontinline,PlatformAbiTest::machineCall -Djava.library.path=. -cp "$classpath")
java -XX:+UseG1GC "${vm[@]}" PlatformAbiTest
case $(uname -m) in
    x86_64|amd64)
        # Force the indirect narrow bridge, independently of inlining and DLL placement.
        java -XX:+UseG1GC -Dnalim.noinline=true -Dnalim.nodirect=true "${vm[@]}" PlatformAbiTest ;;
esac
if java -Xmx128m -XX:+UseZGC -version >zgc-probe.log 2>&1; then
    java -XX:+UseZGC "${vm[@]}" PlatformAbiTest
elif grep -Eiq "(ZGC|selected GC).*(not supported|not available)|[Uu]nrecognized VM option 'UseZGC'" zgc-probe.log; then
    echo 'Skipping ZGC: not provided by this VM.' >&2; cat zgc-probe.log >&2
else
    cat zgc-probe.log >&2; exit 1
fi
