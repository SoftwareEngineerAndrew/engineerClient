#!/bin/sh
# Copies the six ADS data pack files from a pack build folder into the jar's resources.
# usage: tools/bundle-ads-packs.sh <packs/out folder>
# Only the six files below are copied; reports and other files in the folder are ignored.
set -eu
src=${1:?usage: tools/bundle-ads-packs.sh <folder with the built pack files>}
dest=$(cd "$(dirname "$0")/.." && pwd)/src/resources/assets/engineerclient/ads/packs
files="rooms.index.json.gz rooms.blocks.bin layouts.json.gz mobs.json.gz runstate.json.gz traps.json"
for f in $files; do
  [ -f "$src/$f" ] || { echo "missing $src/$f" >&2; exit 1; }
done
mkdir -p "$dest"
for f in $files; do
  cp "$src/$f" "$dest/$f"
  echo "bundled $f ($(wc -c < "$dest/$f") bytes)"
done
echo "Done. Rebuild the mod to ship them. The old files are replaced in place; review the change with git status."
