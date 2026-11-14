#!/usr/bin/env bash
# Boots a Fabric 26.3 server with one mods.json config and reports through summarize.py.
#   run_smoke.sh CONFIG        env: ACCEPT_EULA=true to write eula=true, JAVA=path to a Java 25 binary
set -uo pipefail
CONFIG="${1:?config name}"
JAVA="${JAVA:-java}"
HERE="$(cd "$(dirname "$0")" && pwd)"
RUN="${RUN_DIR:-$PWD/smoke-run}"
# Fabric's documented server-launcher endpoint, built from the versions of the launcher jar the user
# uploaded (game 26.3, loader 0.20.1, installer 1.1.2). Not reachable from the authoring sandbox: unverified.
LAUNCHER_URL="https://meta.fabricmc.net/v2/versions/loader/26.3/0.20.1/1.1.2/server/jar"

rm -rf "$RUN" && mkdir -p "$RUN/mods" && cd "$RUN" || exit 2
if [ "${ACCEPT_EULA:-false}" != "true" ]; then
  echo "::error::eula not accepted: rerun with accept_mojang_eula ticked (https://aka.ms/MinecraftEULA)"; exit 2
fi
echo "eula=true" > eula.txt
printf 'online-mode=false\nserver-port=25565\nview-distance=2\nsimulation-distance=2\n' > server.properties

curl -fsSL --retry 3 "$LAUNCHER_URL" -o fabric-server.jar || { echo "::error::could not download the Fabric server launcher from $LAUNCHER_URL"; exit 1; }
python3 "$HERE/fetch_mods.py" "$CONFIG" mods || exit 1

mkfifo stdin.fifo
"$JAVA" -Xmx1G -jar fabric-server.jar nogui < stdin.fifo > server.log 2>&1 &
SERVER=$!
exec 3> stdin.fifo
for _ in $(seq 1 420); do
  grep -q 'Done (' server.log 2>/dev/null && break
  kill -0 "$SERVER" 2>/dev/null || break
  sleep 1
done
echo "stop" >&3 2>/dev/null
for _ in $(seq 1 60); do kill -0 "$SERVER" 2>/dev/null || break; sleep 1; done
kill "$SERVER" 2>/dev/null
exec 3>&-
tail -n 60 server.log
python3 "$HERE/summarize.py" "$CONFIG" server.log
