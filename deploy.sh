#!/usr/bin/env bash
# Build and deploy ascent into the test instance.
#
# Atomic install (temp-write + rename): Fabric/Knot lazily loads classes from the
# jar, so a plain `cp` over a live jar can corrupt a running game mid-copy.
set -euo pipefail
cd "$(dirname "$0")"

MODS_DIR="${ASCENT_MODS_DIR:-$HOME/.local/share/PrismLauncher/instances/26.1.2 Ascent/minecraft/mods}"
JAR="build/libs/ascent-0.1.0.jar"

./gradlew build -q

TARGET="$MODS_DIR/$(basename "$JAR")"
install -m 0644 "$JAR" "$TARGET.new"
mv -f "$TARGET.new" "$TARGET"
sha256sum "$JAR" "$TARGET"
