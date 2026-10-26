#!/usr/bin/env bash
# Starts skill previews the moment origin/main moves (a push), without waiting for the dev deploy.
#   scripts/skill-preview-watch.sh up | down | status | run | once
# `up` starts a detached singleton poller (git fetch every POLL_S, default 5 s). It owns its own detached worktree
# (.claude/worktrees/skill-preview) and its own build dir, so it never contends with the deploy's gradle. On a new origin/main sha it
# checks the sha out and runs scripts/skill-preview-auto.sh <last> <sha> (content-only pushes: no Gradle at all; the first run
# and every code change compile once). Last handled sha: .claude/state/dev-deploy/preview-last-sha. Log: preview.log.
set -uo pipefail
ROOT=/IdeaProjects/Astera
PW=$ROOT/.claude/worktrees/skill-preview
STATE=$ROOT/.claude/state/dev-deploy
POLL_S=${POLL_S:-5}
mkdir -p "$STATE"

once() {
  git -C "$ROOT" fetch -q origin main 2>/dev/null || return 0
  local sha last
  sha=$(git -C "$ROOT" rev-parse origin/main)
  last=$(cat "$STATE/preview-last-sha" 2>/dev/null || true)
  [ "$sha" != "$last" ] || return 0
  if [ ! -d "$PW" ]; then git -C "$ROOT" worktree add --detach "$PW" "$sha" >/dev/null 2>&1 || return 0; fi
  if [ -z "$last" ]; then last=$sha; fi   # first run: nothing to diff against, start from the next push
  git -C "$PW" checkout -q --detach "$sha" 2>/dev/null || return 0
  printf '%s\n' "$sha" > "$STATE/preview-last-sha"
  bash "$PW/scripts/skill-preview-auto.sh" "$last" "$sha"
}

case "${1:-status}" in
  run)
    exec 8>"$STATE/preview-watch.lock"
    flock -n 8 || exit 0
    while :; do once; sleep "$POLL_S"; done
    ;;
  once) once ;;
  up)
    "$0" down
    setsid nohup bash "$0" run >> "$STATE/preview-watch.out" 2>&1 < /dev/null &
    echo "skill-preview watcher started"
    ;;
  down) pkill -f "skill-preview-watch.sh run" || true ;;
  status) pgrep -fa "skill-preview-watch.sh run" || echo "not running"; tail -n 5 "$STATE/preview.log" 2>/dev/null ;;
  *) echo "usage: $0 up|down|status|run|once" >&2; exit 2 ;;
esac
