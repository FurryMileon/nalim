#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
output=${1:-"$script_dir/nalim-runtime"}

if [ -z "${JAVA_HOME:-}" ]; then
    echo "JAVA_HOME must point to the JDK that will be packaged" >&2
    exit 1
fi

jlink="$JAVA_HOME/bin/jlink"
release_file="$JAVA_HOME/release"
java="$JAVA_HOME/bin/java"
if [ ! -x "$jlink" ] || [ ! -x "$java" ] || [ ! -f "$release_file" ]; then
    echo "JAVA_HOME is not a complete JDK: $JAVA_HOME" >&2
    exit 1
fi

feature=$(sed -n 's/^JAVA_VERSION="\([0-9][0-9]*\).*"/\1/p' "$release_file")
case "$feature" in
    ''|*[!0-9]*)
        echo "Could not read JAVA_VERSION from $release_file" >&2
        exit 1
        ;;
esac
if [ "$feature" -lt 21 ]; then
    echo "The Nalim runtime image requires JDK 21 or newer" >&2
    exit 1
fi
# With JEP 493 a full JDK may have no jmods directory. Query the running
# image and let its own jlink choose the matching module source.
modules=$("$java" --list-modules)
modules=$(printf '%s\n' "$modules" | cut -d@ -f1)
if [ ! -f "$JAVA_HOME/jmods/java.base.jmod" ]; then
    # jlink cannot put itself (or jpackage, which requires it) in a
    # second-generation image without packaged modules.
    modules=$(printf '%s\n' "$modules" | grep -vx -e jdk.jlink -e jdk.jpackage)
fi
modules=$(printf '%s\n' "$modules" | paste -sd, -)
case ",$modules," in
    *,jdk.internal.vm.ci,*) ;;
    *) echo "This JDK does not contain jdk.internal.vm.ci; Nalim requires a JVMCI-capable HotSpot build" >&2; exit 1 ;;
esac
if [ -e "$output" ]; then
    echo "Output already exists: $output" >&2
    echo "Remove it explicitly before rebuilding" >&2
    exit 1
fi

if [ "$feature" -lt 25 ]; then
    launch_options='-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI --add-modules=jdk.internal.vm.ci --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED'
else
    launch_options='--add-modules=jdk.internal.vm.ci --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED'
fi
if [ "$feature" -ge 26 ]; then
    launch_options="$launch_options -XX:-UseCodeCacheFlushing"
fi

"$jlink" \
    --add-modules "$modules" \
    --add-options="$launch_options" \
    --compress zip-6 \
    --strip-debug \
    --no-header-files \
    --no-man-pages \
    --output "$output"

printf 'Created Nalim JDK %s runtime: %s\n' "$feature" "$output"
printf 'Baked JVM options: %s\n' "$launch_options"
