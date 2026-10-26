#!/usr/bin/env bash
# Launch a Codex implementation task in its own worktree and run it in the FOREGROUND.
# Usage: scripts/codex-task.sh <task-id> <prompt-file> [base-ref]
# Meant to be launched via Bash `run_in_background: true` (the gate .claude/hooks/gate-codex-lane.py
# denies any other launch). Claude Code then delivers ONE notification when this script exits, with
# Codex's exit code. Never detach it (nohup/setsid/disown/&) and never poll/tail/sleep.
# Run from the track worktree. Creates .claude/worktrees/<task-id> on branch wp/<task-id>
# from base-ref (default: HEAD of the checkout it is invoked from, i.e. the track branch),
# prepends .claude/prompts/codex-preamble.md, runs `codex exec -p astar-implement`.
# Env: CODEX_TIMEOUT (seconds, default 5400).
# Logs: $SCRATCH/<task-id>.log (ends with an EXIT=<code> line), last message: $SCRATCH/<task-id>-last.md
set -euo pipefail

if [ $# -lt 2 ] || [ $# -gt 3 ]; then
  echo "usage: $0 <task-id> <prompt-file> [base-ref]" >&2
  exit 64
fi
TASK_ID="$1"
PROMPT_FILE="$(readlink -f "$2")"
[[ "$TASK_ID" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "bad task-id: $TASK_ID" >&2; exit 64; }
[ -f "$PROMPT_FILE" ] || { echo "prompt file not found: $PROMPT_FILE" >&2; exit 66; }

# Resolve the primary checkout even when invoked from inside a worktree.
COMMON="$(git rev-parse --path-format=absolute --git-common-dir)"
ROOT="$(dirname "$COMMON")"
WT="$ROOT/.claude/worktrees/$TASK_ID"
BRANCH="wp/$TASK_ID"
SCRATCH="${CODEX_SCRATCH:-/tmp/claude-1000/codex}"
mkdir -p "$SCRATCH"
LOG="$SCRATCH/$TASK_ID.log"
LAST="$SCRATCH/$TASK_ID-last.md"
PREAMBLE="$ROOT/.claude/prompts/codex-preamble.md"
[ -f "$PREAMBLE" ] || PREAMBLE="$(dirname "$0")/../.claude/prompts/codex-preamble.md"

if [ -e "$WT" ]; then
  echo "worktree already exists: $WT" >&2
  exit 73
fi
BASE="$(git rev-parse --verify "${3:-HEAD}^{commit}")"
git -C "$ROOT" worktree add "$WT" -b "$BRANCH" "$BASE"

FULL="$SCRATCH/$TASK_ID-prompt.md"
{ cat "$PREAMBLE"; printf '\n\n---\n\n# TASK (%s)\n\n' "$TASK_ID"; cat "$PROMPT_FILE"; } > "$FULL"

echo "started $TASK_ID worktree=$WT branch=$BRANCH log=$LOG last=$LAST"

rc=0
timeout "${CODEX_TIMEOUT:-5400}" codex exec -p astar-implement -s danger-full-access -C "$WT" -o "$LAST" - < "$FULL" > "$LOG" 2>&1 || rc=$?
echo "EXIT=$rc" >> "$LOG"
exit "$rc"
