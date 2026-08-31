#!/bin/sh
set -eu
classes_dir=out/classes
rm -rf "$classes_dir"
mkdir -p "$classes_dir"

javac --add-modules jdk.internal.vm.ci \
      --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
      --add-exports jdk.internal.vm.ci/jdk.vm.ci.code=ALL-UNNAMED \
      --add-exports jdk.internal.vm.ci/jdk.vm.ci.code.site=ALL-UNNAMED \
      --add-exports jdk.internal.vm.ci/jdk.vm.ci.hotspot=ALL-UNNAMED \
      --add-exports jdk.internal.vm.ci/jdk.vm.ci.meta=ALL-UNNAMED \
      --add-exports jdk.internal.vm.ci/jdk.vm.ci.runtime=ALL-UNNAMED \
      --source 11 --target 11 \
      -d "$classes_dir" src/one/nalim/*.java

jar cfm nalim.jar MANIFEST.MF -C "$classes_dir" one -C . fabric.mod.json
