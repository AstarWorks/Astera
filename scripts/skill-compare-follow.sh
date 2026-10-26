#!/usr/bin/env bash
# Keeps the skill-compare page (bun tools/skill-compare/server.mjs, :8097) in lock-step with what is deployed on dev.
#
#   make skill-compare-up | skill-compare-down | skill-compare-status
#   scripts/skill-compare-follow.sh up|down|status|run|once
#
# A tiny watcher of its own (dev-autodeploy.sh is not touched). Every SC_POLL seconds it:
#   1. reads the last "deployed <sha>" of the dev autodeploy log
#   2. moves the serve checkout (SC_ROOT, a detached worktree used ONLY for serving) to that sha:  git checkout -f --detach <sha>
#      -> case data, beat sheets, round notes, the page code match dev within seconds
#   3. restarts the server only when tools/skill-compare changed (bun install when package.json / bun.lock did)
#   4. restarts the server whenever it is not running
# The server itself pushes /api/events (SSE) so open pages refresh without a reload, and judges every video's freshness against the dev sha
# (tools/skill-compare/lib/freshness.mjs).
#
# Env (defaults in brackets): SC_PRIMARY [/IdeaProjects/Astera]  SC_ROOT [$SC_PRIMARY/.claude/worktrees/skill-compare-serve]
#   SC_STATE [$SC_PRIMARY/.claude/state/skill-compare]  SC_DEPLOY_LOG [$SC_PRIMARY/.claude/state/dev-deploy/deploy.log]
#   SC_PORT [8097]  SC_HOST [0.0.0.0]  SC_PUBLISHED [$SC_PRIMARY/build/skill-compare-published]  SC_VFX_SHOT [$SC_PRIMARY/build/vfx-shot]
#   SC_POLL [3]  SC_REMOTE_FETCH [1: git fetch origin when the deployed sha is not local yet]
set -uo pipefail

HERE="${SC_HERE:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)}"   # SC_HERE: set when running from the private copy (see up)
PRIMARY="${SC_PRIMARY:-/IdeaProjects/Astera}"
ROOT="${SC_ROOT:-$PRIMARY/.claude/worktrees/skill-compare-serve}"
STATE="${SC_STATE:-$PRIMARY/.claude/state/skill-compare}"
DEPLOY_LOG="${SC_DEPLOY_LOG:-$PRIMARY/.claude/state/dev-deploy/deploy.log}"
PORT="${SC_PORT:-8097}"
HOST="${SC_HOST:-0.0.0.0}"
PUBLISHED="${SC_PUBLISHED:-$PRIMARY/build/skill-compare-published}"
VFX="${SC_VFX_SHOT:-$PRIMARY/build/vfx-shot}"
POLL="${SC_POLL:-3}"
mkdir -p "$STATE"
# bun's shared cache is not writable for every user on this host; a private one always works
export BUN_INSTALL_CACHE_DIR="${BUN_INSTALL_CACHE_DIR:-/tmp/claude-1000/bun-cache}"

slog() { printf '%s %s\n' "$(date -Is)" "$*" | tee -a "$STATE/follow.log" >&2; }
latest_deployed() { grep -E ' deployed [0-9a-f]{7,40}( |$)' "$DEPLOY_LOG" 2>/dev/null | tail -1 | awk '{print $3}'; }
alive() { [ -f "$1" ] && kill -0 "$(cat "$1" 2>/dev/null)" 2>/dev/null; }
head_of() { git -C "$ROOT" rev-parse HEAD 2>/dev/null; }

ensure_root() {
  [ -e "$ROOT/.git" ] && return 0
  local sha; sha="$(latest_deployed)"; [ -n "$sha" ] || sha=origin/main
  slog "creating serve worktree $ROOT at $sha"
  git -C "$PRIMARY" worktree add --detach "$ROOT" "$sha" >> "$STATE/follow.log" 2>&1
}

stop_server() {
  alive "$STATE/server.pid" || { rm -f "$STATE/server.pid"; return 0; }
  local pid; pid="$(cat "$STATE/server.pid")"
  kill "$pid" 2>/dev/null
  for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$pid" 2>/dev/null || break; sleep 0.3; done
  kill -9 "$pid" 2>/dev/null
  rm -f "$STATE/server.pid"
}

start_server() {
  local dir="$ROOT/tools/skill-compare"
  [ -d "$dir" ] || { slog "server dir missing: $dir"; return 1; }
  [ -d "$dir/node_modules" ] || (cd "$dir" && bun install >> "$STATE/server.log" 2>&1)
  # setsid + nohup: the server outlives this shell; fds 8/9 closed like the other detached helpers
  # the inner bash records its own pid and execs bun, so server.pid is the real server even though setsid may fork
  ( cd "$dir" && PORT="$PORT" HOST="$HOST" SKILL_COMPARE_PUBLISHED="$PUBLISHED" SKILL_COMPARE_CANONICAL=1 SKILL_COMPARE_VFX_SHOT="$VFX" \
      SKILL_COMPARE_DEPLOY_LOG="$DEPLOY_LOG" setsid nohup bash -c 'echo $$ > "$1"; exec bun server.mjs' _ "$STATE/server.pid" >> "$STATE/server.log" 2>&1 9>&- 8>&- < /dev/null & )
  sleep 0.5
  date +%s > "$STATE/server.started"
  slog "server started pid=$(cat "$STATE/server.pid") port=$PORT root=$ROOT head=$(head_of | cut -c1-9)"
}

# one reconcile pass; echoes nothing, logs what it did
reconcile() {
  ensure_root || { slog "serve worktree unavailable"; return 1; }
  local want have restart=0 files
  want="$(latest_deployed)"; have="$(head_of)"
  if [ -n "$want" ] && [ "$want" != "$have" ] && [[ "$have" != "$want"* ]]; then
    if ! git -C "$ROOT" cat-file -e "$want^{commit}" 2>/dev/null; then
      [ "${SC_REMOTE_FETCH:-1}" = 1 ] && git -C "$ROOT" fetch -q origin >> "$STATE/follow.log" 2>&1
    fi
    if git -C "$ROOT" cat-file -e "$want^{commit}" 2>/dev/null; then
      files="$(git -C "$ROOT" diff --name-only "$have" "$want" -- tools/skill-compare 2>/dev/null)"
      if git -C "$ROOT" checkout -q -f --detach "$want" >> "$STATE/follow.log" 2>&1; then
        slog "serve checkout ${have:0:9} -> ${want:0:9} (skill-compare files changed: $(printf '%s' "$files" | grep -c .))"
        [ -n "$files" ] && restart=1
        if printf '%s\n' "$files" | grep -qE '/(package.json|bun.lock)$'; then (cd "$ROOT/tools/skill-compare" && bun install >> "$STATE/server.log" 2>&1); fi
      else slog "checkout $want failed"; fi
    else
      [ "$(cat "$STATE/missing-sha" 2>/dev/null)" = "$want" ] || { slog "deployed sha ${want:0:9} is not in the repo yet; retrying"; echo "$want" > "$STATE/missing-sha"; }
    fi
  fi
  if [ "$restart" = 1 ]; then slog "tools/skill-compare changed: restarting server"; stop_server; fi
  if ! alive "$STATE/server.pid"; then
    # crash loop guard: a server that died within 5 s of its start waits 10 s before the next try
    local started now; started="$(cat "$STATE/server.started" 2>/dev/null || echo 0)"; now="$(date +%s)"
    [ "$restart" = 0 ] && [ -f "$STATE/server.pid" ] && [ $((now - started)) -lt 5 ] && { sleep 10; }
    [ "$restart" = 0 ] && slog "server not running: starting"
    start_server
  fi
}

run_loop() {
  # the checkouts below rewrite this very file: run from a private copy
  if [ -z "${SC_HERE:-}" ]; then
    cp "$0" "$STATE/follow.run.sh" && SC_HERE="$HERE" exec bash "$STATE/follow.run.sh" run
  fi
  exec 8>"$STATE/follow.lock"
  flock -n 8 || { echo "already running" >&2; exit 0; }
  echo $$ > "$STATE/follow.pid"
  trap 'stop_server; rm -f "$STATE/follow.pid"; exit 0' TERM INT
  slog "follower up: root=$ROOT deploy-log=$DEPLOY_LOG port=$PORT published=$PUBLISHED"
  while :; do
    reconcile
    sleep "$POLL" & wait $!
  done
}

case "${1:-status}" in
  up)
    if alive "$STATE/follow.pid"; then echo "already running (pid $(cat "$STATE/follow.pid"))"; exit 0; fi
    ensure_root
    SC_HERE="" setsid nohup bash "$0" run > /dev/null 2>&1 9>&- < /dev/null &
    sleep 1; bash "$0" status ;;
  run) run_loop ;;
  once) reconcile ;;
  down)
    if alive "$STATE/follow.pid"; then kill "$(cat "$STATE/follow.pid")"; sleep 1; fi
    stop_server; echo "stopped" ;;
  status)
    w="$(latest_deployed)"; h="$(head_of)"
    echo "follower : $(alive "$STATE/follow.pid" && echo "up (pid $(cat "$STATE/follow.pid"))" || echo DOWN)"
    echo "server   : $(alive "$STATE/server.pid" && echo "up (pid $(cat "$STATE/server.pid")) http://localhost:$PORT" || echo DOWN)"
    echo "dev sha  : ${w:0:9}"
    echo "serve sha: ${h:0:9} $([ -n "$w" ] && [[ "$h" == "$w"* || "$w" == "$h"* ]] && echo "(in sync)" || echo "(BEHIND dev)")  [$ROOT]"
    echo "--- follow.log"; tail -n 6 "$STATE/follow.log" 2>/dev/null
    echo "--- server.log"; tail -n 3 "$STATE/server.log" 2>/dev/null ;;
  *) echo "usage: $0 up|down|status|run|once" >&2; exit 2 ;;
esac
