#!/usr/bin/env bash
# Launch a Codex implementation task in its own worktree, in the background.
# Usage: scripts/codex-task.sh <task-id> <prompt-file>
# Creates .claude/worktrees/<task-id> on branch wp/<task-id> from local main,
# prepends .claude/prompts/codex-preamble.md, runs `codex exec -p astar-implement`.
# Logs: $SCRATCH/<task-id>.log (ends with an EXIT=<code> line), last message: $SCRATCH/<task-id>-last.md
set -euo pipefail

if [ $# -ne 2 ]; then
  echo "usage: $0 <task-id> <prompt-file>" >&2
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
git -C "$ROOT" worktree add "$WT" -b "$BRANCH" main

FULL="$SCRATCH/$TASK_ID-prompt.md"
{ cat "$PREAMBLE"; printf '\n\n---\n\n# TASK (%s)\n\n' "$TASK_ID"; cat "$PROMPT_FILE"; } > "$FULL"

nohup bash -c '
  codex exec -p astar-implement -s danger-full-access -C "$1" -o "$2" - < "$3" > "$4" 2>&1
  echo "EXIT=$?" >> "$4"
' _ "$WT" "$LAST" "$FULL" "$LOG" > /dev/null 2>&1 &

echo "started $TASK_ID pid=$! worktree=$WT branch=$BRANCH"
echo "log=$LOG (EXIT= line when done)  last=$LAST"
