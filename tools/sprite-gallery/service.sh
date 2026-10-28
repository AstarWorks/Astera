#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
STATE="$ROOT/build/sprite-gallery"
mkdir -p "$STATE"
alive() { [[ -f "$STATE/server.pid" ]] && kill -0 "$(cat "$STATE/server.pid")" 2>/dev/null; }
# Serialize lifecycle commands; the detached server must not inherit the lock.
exec 9>"$STATE/service.lock"
flock 9
case "${1:-status}" in
  up)
    if alive; then echo "already running (pid $(cat "$STATE/server.pid"))"; exit 0; fi
    (cd "$ROOT" && setsid nohup bash -c 'echo $$ > "$1"; exec bun tools/sprite-gallery/server.mjs' _ "$STATE/server.pid" >> "$STATE/server.log" 2>&1 9>&- < /dev/null &)
    sleep 1
    if ! alive; then tail -n 15 "$STATE/server.log"; exit 1; fi
    echo "running (pid $(cat "$STATE/server.pid")), http://192.168.11.5:${PORT:-8098}" ;;
  down)
    if alive; then kill "$(cat "$STATE/server.pid")"; fi
    rm -f "$STATE/server.pid"
    echo stopped ;;
  status)
    if alive; then echo "running (pid $(cat "$STATE/server.pid"))"; else echo DOWN; fi
    tail -n 3 "$STATE/server.log" 2>/dev/null || true ;;
  *) echo "usage: $0 up|down|status" >&2; exit 2 ;;
esac
