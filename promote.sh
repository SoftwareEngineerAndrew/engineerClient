#!/usr/bin/env bash
# promote.sh [-n] "<commit message>": copies this repo's committed HEAD to the public
# undonecoffee/adm as ADM (Advanced Dungeons Mod), without the extras that only ship in this dev build:
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
# Every Minecraft version with a release (tag release-<mc>, jar ADM-<mc>.jar), newest first.
VERSIONS="26.3 26.2 26.1.2"
# ADM's own version (the jar's mod version and the release notes); this dev build keeps its date+commit versions.
export ADM_VERSION="1.0.1"

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
  "$PUB/src/resources/coffeeclient.mixins.json" "$PUB/src/resources/coffeeclient.odin.mixins.json" "$PUB/src/resources/devgineerclient.mixins.json" \
  "$PUB/src/resources/devgineerclient.odin.mixins.json" \
  "$PUB/src/resources/rotation"

FMJ="$PUB/src/resources/fabric.mod.json"
sed -i -E \
  -e 's/, "(coffeeclient|devgineerclient)(\.odin)?\.mixins\.json"//g' \
  -e '/"breaks": \{ "coffeeclient"/d' \
  -e '/"value": "com\.(coffeeclient|devgineerclient)\./d' \
  -e 's/("value": "com\.engineerclient\.EngineerClient" \}),$/\1/' \
  "$FMJ"
jq -e . "$FMJ" >/dev/null || { echo "promote: fabric.mod.json is not valid JSON after stripping" >&2; exit 1; }

# The README is just the download table, written fresh on every promote so its links always point at the
# release jars (newest Minecraft first, in bold).
{
  echo "| Version | Download |"
  echo "|---|---|"
  for v in $VERSIONS; do
    link="[ADM-$v.jar](https://github.com/undonecoffee/adm/releases/download/release-$v/ADM-$v.jar)"
    if [ "$v" = "${VERSIONS%% *}" ]; then echo "| **$v** | **$link** |"; else echo "| $v | $link |"; fi
  done
} > "$PUB/README.md"

# This repo builds Engineer Client; the public release is ADM. Mod id, name, panel, prefix, commands, folders
# and jar are renamed in the public tree only; packages stay com.engineerclient. Fails when a rename it relies
# on matched nothing, or when the old name still shows somewhere. When adding user-visible name/path strings,
# keep them in forms a rule below rewrites, or add a rule.
node - "$PUB" <<'JS' || { echo "promote: renaming to ADM failed" >&2; exit 1; }
const fs = require('fs'), path = require('path');
const root = process.argv[2];

const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) { if (e.name !== '.git' && e.name !== 'build' && e.name !== 'libs') walk(p); }
    else if (/\.(kt|java|json|kts|yml|md)$/.test(e.name)) files.push(p);
  }
})(root);

// [pattern, replacement, required]: a required rule must match somewhere in the tree.
const rules = [
  // Mod metadata, jar and releases.
  ['"id": "engineerclient",', '"id": "adm",', true],
  ['"name": "Engineer Client",', '"name": "ADM",', true],
  ['"description": "An Odin addon for Hypixel SkyBlock dungeons:', '"description": "Advanced Dungeons Mod, an Odin addon for Hypixel SkyBlock dungeons:', true],
  // An old Engineer Client jar left in mods is then a duplicate (an error naming both), not a second copy.
  ['  "license": "All-Rights-Reserved",\n', '  "license": "All-Rights-Reserved",\n  "provides": ["engineerclient"],\n', true],
  ['archivesName.set("engineerclient")', 'archivesName.set("adm")', true],
  ['# releases/download/release-<channel>/EngineerClient-<mc>.jar', '# releases/download/release-<channel>/ADM-<mc>.jar', false],
  ['jar="EngineerClient-$MC.jar"', 'jar="ADM-$MC.jar"', true],
  ['echo "EC_VERSION=$(date -u +%Y.%-m.%-d)+${GITHUB_SHA::7}"', 'echo "EC_VERSION=' + process.env.ADM_VERSION + '"', true],
  ['**[Download EngineerClient for Minecraft %s](%s)**', '**[Download ADM for Minecraft %s](%s)**', true],
  ['--title "EngineerClient for Minecraft $MC"', '--title "ADM for Minecraft $MC"', true],
  // Panel, prefix, commands, text.
  ['Category.custom("Engineer Client"', 'Category.custom("ADM"', true],
  ['const val PREFIX = "§8[§6EC§8] "', 'const val PREFIX = "§8[§6ADM§8] "', true],
  ['Added by engineerClient.', 'Added by ADM.', true],
  ['desc = "engineerClient\'s solver', 'desc = "ADM\'s solver', false],
  ['"EC Dynamic Term Roles is OFF", "enable it in the Engineer Client panel"', '"ADM Dynamic Term Roles is OFF", "enable it in the ADM panel"', false],
  ['ENGINEER_SPLITS("Engineer Splits")', 'ENGINEER_SPLITS("ADM Splits")', true],
  ['desc = "Odin Splits, or Engineer Splits:', 'desc = "Odin Splits, or ADM Splits:', false],
  ['desc = "Engineer Splits: ', 'desc = "ADM Splits: ', false],
  ['Look: Engineer Splits', 'Look: ADM Splits', false],
  // Settings and folders (ConfigMigration moves an Engineer Client install's over on first start).
  ['private const val NAME = "engineerclient"', 'private const val NAME = "adm"', true],
  ['ModuleConfig("engineerclient.json")', 'ModuleConfig("adm.json")', true],
  ['.resolve("engineerclient.json")', '.resolve("adm.json")', true],
  ['getModContainer("engineerclient")', 'getModContainer("adm")', true],
  ['"engineerclient ${FabricLoader', '"adm ${FabricLoader', false],
  ['resolve("config").resolve("engineerclient")', 'resolve("config").resolve("adm")', true],
  ['resolve("logs").resolve("engineerclient")', 'resolve("logs").resolve("adm")', true],
  ['configDir.resolve("engineerclient")', 'configDir.resolve("adm")', false],
  ['config/engineerclient', 'config/adm', true],
  ['addons/engineerclient.json', 'addons/adm.json', false],
  ['logs/engineerclient', 'logs/adm', false],
];

const hits = new Array(rules.length).fill(0);
for (const f of files) {
  let s = fs.readFileSync(f, 'utf8');
  const o = s;
  rules.forEach(([a, b], i) => {
    if (a instanceof RegExp) { const n = (s.match(a) || []).length; if (n) { hits[i] += n; s = s.replace(a, b); } }
    else if (s.includes(a)) { hits[i] += s.split(a).length - 1; s = s.split(a).join(b); }
  });
  if (s !== o) fs.writeFileSync(f, s);
}

let bad = false;
rules.forEach(([a], i) => { if (rules[i][2] && !hits[i]) { console.error('promote: nothing matched ' + JSON.stringify(String(a))); bad = true; } });
// What players would still see of the old name.
const leftovers = [/Category\.custom\("Engineer Client"/, /"name": "Engineer Client"/, /"id": "engineerclient"/, /§6EC§8/, /config\/engineerclient/, /ModuleConfig\("engineerclient/, /Added by engineerClient/, /undonecoffee\/EngineerClient/];
for (const f of files) {
  const s = fs.readFileSync(f, 'utf8');
  for (const re of leftovers) if (re.test(s)) { console.error('promote: ' + path.relative(root, f) + ' still has ' + re); bad = true; }
}
process.exit(bad ? 1 : 0);
JS
jq -e . "$FMJ" >/dev/null || { echo "promote: fabric.mod.json is not valid JSON after renaming" >&2; exit 1; }

if grep -rniE 'coffeeclient|devgineerclient' "$PUB/src" "$PUB/build.gradle.kts"; then
  echo "promote: the lines above still reference the extras" >&2; exit 1
fi

# Must build without the extras. Untracked compile-only jars (libs/, e.g. the Odin 26.3 port, which
# CI downloads itself) are borrowed for the check and never committed.
# The compile check runs in a copy kept between runs (per branch), with the Gradle daemon: only
# what changed since the last promote is recompiled, instead of the whole tree from cold.
CHECK="${XDG_CACHE_HOME:-$HOME/.cache}/ec-promote/${BRANCH//\//_}"
mkdir -p "$CHECK"
rsync -a --delete --exclude .git --exclude build --exclude .gradle --exclude .kotlin --exclude libs "$PUB/" "$CHECK/"
[ -d "$TEAM/libs" ] && rsync -a --delete "$TEAM/libs/" "$CHECK/libs/"
(
  cd "$CHECK"
  export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
  # The default 512 MB heap runs out on EngineerLook's tables.
  if ! "$GRADLE" -q -Dorg.gradle.jvmargs=-Xmx3g -Pkotlin.daemon.jvmargs=-Xmx3g compileKotlin compileJava > build.log 2>&1; then
    grep -v '^w:' build.log >&2; echo "promote: the public tree does not compile" >&2; exit 1
  fi
)

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
echo "promote: pushed $(git -C "$PUB" rev-parse --short "$BRANCH") to undonecoffee/adm $BRANCH"
for v in $VERSIONS; do
  curl -sfIL -o /dev/null "https://github.com/undonecoffee/adm/releases/download/release-$v/ADM-$v.jar" \
    || echo "promote: warning, the README download for $v is not there (release-$v has no ADM-$v.jar)" >&2
done
