#!/usr/bin/env bash
# Build and deploy Engineer Client into the test instance.
#
# Atomic install (temp-write + rename): Fabric/Knot lazily loads classes from the
# jar, so a plain `cp` over a live jar can corrupt a running game mid-copy.
set -euo pipefail
cd "$(dirname "$0")"

MODS_DIR="${EC_MODS_DIR:-${BRW_MODS_DIR:-$HOME/.local/share/PrismLauncher/instances/26.1.2 BRW/minecraft/mods}}"
VERSION="$(grep '^mod_version=' gradle.properties | cut -d= -f2)"
JAR="build/libs/engineerclient-$VERSION.jar"

./gradlew build -q

# clear stale ascent jars so the instance never loads two versions
rm -f "$MODS_DIR"/ascent-*.jar "$MODS_DIR"/bloodrushwaypoints-*.jar "$MODS_DIR"/engineerclient-*.jar

TARGET="$MODS_DIR/$(basename "$JAR")"
install -m 0644 "$JAR" "$TARGET.new"
mv -f "$TARGET.new" "$TARGET"
sha256sum "$JAR" "$TARGET"
