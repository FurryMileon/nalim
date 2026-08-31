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
module_path="$JAVA_HOME/jmods"
if [ ! -x "$jlink" ] || [ ! -f "$release_file" ] || [ ! -d "$module_path" ]; then
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
    --module-path "$module_path" \
    --add-modules ALL-MODULE-PATH \
    --add-options="$launch_options" \
    --compress zip-6 \
    --strip-debug \
    --no-header-files \
    --no-man-pages \
    --output "$output"

printf 'Created Nalim JDK %s runtime: %s\n' "$feature" "$output"
printf 'Baked JVM options: %s\n' "$launch_options"
