#!/usr/bin/env bash
# promote.sh [-n] "<commit message>": copies this repo's committed HEAD to the public
# undonecoffee/EngineerClient, without the extras that only ship in this dev build:
#   src/coffee/   Coffee Client features   (+ src/resources/coffeeclient{,.odin}.mixins.json)
#   src/dev/      Devgineer Client features (+ src/resources/devgineerclient.mixins.json)
#   promote.sh    this script
# fabric.mod.json loses their entrypoints, mixin configs and the "breaks" line. The result must
# compile on its own before it is committed. One squashed commit lands on the public branch of the
# same name (main, or mc/<version> for an older Minecraft; run it from that branch's worktree) and
# is pushed, which rebuilds that branch's release. -n: stop before the commit and leave the
# change in the public clone to look at.
set -euo pipefail
export PATH="$HOME/.nix-profile/bin:/run/current-system/sw/bin:$PATH"

DRY=0
[ "${1:-}" = "-n" ] && { DRY=1; shift; }
MSG="${1:-}"
[ -n "$MSG" ] || [ "$DRY" = 1 ] || { echo "usage: promote.sh [-n] \"<commit message>\"" >&2; exit 1; }

TEAM="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
PUB="${EC_PUBLIC:-$HOME/Projects/engineerclient-public}"
GRADLE="$HOME/.cache/ec-build/gradle-9.6.1/bin/gradle"

[ -z "$(git -C "$PUB" status --porcelain)" ] || { echo "promote: $PUB has uncommitted changes" >&2; exit 1; }
git -C "$PUB" fetch -q origin
BRANCH="$(git -C "$TEAM" branch --show-current)"
if git -C "$PUB" rev-parse -q --verify "origin/$BRANCH" >/dev/null; then
  git -C "$PUB" checkout -q -B "$BRANCH" "origin/$BRANCH"
else
  git -C "$PUB" checkout -q -B "$BRANCH" origin/main
fi

# Replace the public tree with the dev HEAD, then drop the extras.
git -C "$PUB" rm -rq --ignore-unmatch .
git -C "$TEAM" archive HEAD | tar x -C "$PUB"
rm -rf "$PUB/src/coffee" "$PUB/src/dev" "$PUB/promote.sh" \
  "$PUB/src/resources/coffeeclient.mixins.json" "$PUB/src/resources/coffeeclient.odin.mixins.json" "$PUB/src/resources/devgineerclient.mixins.json"

FMJ="$PUB/src/resources/fabric.mod.json"
sed -i -E \
  -e 's/, "(coffeeclient|devgineerclient)(\.odin)?\.mixins\.json"//g' \
  -e '/"breaks": \{ "coffeeclient"/d' \
  -e '/"value": "com\.(coffeeclient|devgineerclient)\./d' \
  -e 's/("value": "com\.engineerclient\.EngineerClient" \}),$/\1/' \
  "$FMJ"
jq -e . "$FMJ" >/dev/null || { echo "promote: fabric.mod.json is not valid JSON after stripping" >&2; exit 1; }

if grep -rniE 'coffeeclient|devgineerclient' "$PUB/src" "$PUB/build.gradle.kts"; then
  echo "promote: the lines above still reference the extras" >&2; exit 1
fi

# Must build without the extras. Untracked compile-only jars (libs/, e.g. the Odin 26.3 port, which
# CI downloads itself) are borrowed for the check and never committed.
[ -d "$TEAM/libs" ] && cp -r "$TEAM/libs" "$PUB/"
(
  cd "$PUB"
  export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
  # The default 512 MB heap runs out on EngineerLook's tables.
  if ! GRADLE_OPTS="-Xmx3g" "$GRADLE" -q --no-daemon -Pkotlin.daemon.jvmargs=-Xmx3g compileKotlin compileJava > build.log 2>&1; then
    grep -v '^w:' build.log >&2; echo "promote: the public tree does not compile" >&2; exit 1
  fi
)
rm -f "$PUB/build.log"
rm -rf "$PUB/build" "$PUB/.gradle" "$PUB/.kotlin" "$PUB/libs"

git -C "$PUB" add -A
if git -C "$PUB" diff --cached --quiet; then echo "promote: nothing new to promote"; exit 0; fi
git -C "$PUB" diff --cached --stat | tail -n 25

if [ "$DRY" = 1 ]; then
  echo "promote: dry run, change staged in $PUB (git -C $PUB reset --hard to drop it)"
  exit 0
fi

git -C "$PUB" -c user.name=undonecoffee -c user.email=58919771+undonecoffee@users.noreply.github.com \
  commit -q -m "$MSG"
git -C "$PUB" push -q origin "$BRANCH"
git -C "$PUB" checkout -q main
echo "promote: pushed $(git -C "$PUB" rev-parse --short "$BRANCH") to undonecoffee/EngineerClient $BRANCH"
