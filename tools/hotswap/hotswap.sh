#!/usr/bin/env bash
# Build, deploy the jar (for classes not loaded yet, and the next launch), then push changed
# classes into the running "f7 hotswap" game. See README.md.
set -euo pipefail
cd "$(dirname "$0")/../.."
PORT="${EC_HOTSWAP_PORT:-5005}"
EC_MODS_DIR="${EC_MODS_DIR:-$HOME/.local/share/PrismLauncher/instances/f7/minecraft/mods}" ./deploy.sh >/dev/null

# State per game process: the pid listening on the port and its start time.
PID="$(ss -ltnpH "sport = :$PORT" 2>/dev/null | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2 || true)"
if [ -z "$PID" ]; then
    echo "hotswap: no game listening on 127.0.0.1:$PORT (launch the \"f7 hotswap\" instance). Jar is deployed for the next launch."
    exit 2
fi
STATE="${XDG_CACHE_HOME:-$HOME/.cache}/ec-hotswap/$PID-$(awk '{print $22}' "/proc/$PID/stat").state"
# The jar the game launched with: still open, though deploy replaced it on disk.
LAUNCH_JAR=""
for fd in /proc/"$PID"/fd/*; do
    case "$(readlink "$fd" 2>/dev/null)" in *mods/engineerclient-*.jar*) LAUNCH_JAR="$fd"; break;; esac
done
[ -n "$LAUNCH_JAR" ] || { echo "hotswap: can't find the game's engineerclient jar in /proc/$PID/fd"; exit 1; }

JAVA_HOME_DIR="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
VERSION="$(grep '^mod_version=' gradle.properties | cut -d= -f2)"
exec "$JAVA_HOME_DIR/bin/java" tools/hotswap/Hotswap.java "$PORT" "build/libs/engineerclient-$VERSION.jar" "$LAUNCH_JAR" "$STATE"
