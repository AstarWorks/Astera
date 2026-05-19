#!/usr/bin/env bash
# codex_generate.sh — invoke Codex CLI image generation and reliably
# produce a PNG at a target path, working around the nested-sandbox issue
# where Codex's image tool writes to ~/.codex/generated_images/<session>/
# but cannot copy out under bwrap-in-bwrap.
#
# Usage:
#   codex_generate.sh OUT_PNG PROMPT_FILE
#
# Returns 0 on success with OUT_PNG written; non-zero otherwise.
#
# Boundary: human-run / skill-invoked only. Never invoked by Astera build
# or runtime. See tools/texture-gen/README.md and ADR-0018.

set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 OUT_PNG PROMPT_FILE" >&2
    exit 2
fi

OUT_PNG="$1"
PROMPT_FILE="$2"

if [[ ! -f "$PROMPT_FILE" ]]; then
    echo "error: prompt file not found: $PROMPT_FILE" >&2
    exit 1
fi

if ! command -v codex >/dev/null 2>&1; then
    echo "error: codex CLI not on PATH" >&2
    exit 1
fi

WORK=$(dirname "$OUT_PNG")
mkdir -p "$WORK"
LOG="$WORK/codex.stderr.log"

# Capture codex stderr so we can fish out the session ID later.
# Use workspace-write sandbox; if that fails for the actual file write we
# fall back to scraping the generated_images dir.
#
# stdin redirect (< /dev/null): codex reads stdin even when the prompt is
# provided as a positional argument (per `codex exec --help`: piped stdin
# is appended as a <stdin> block). In a foreground TTY this happens to
# EOF immediately, but in background contexts (Bash auto-backgrounded
# long-timeout calls, scripts piped together) stdin stays open as a pipe
# that never closes, and codex hangs forever on the initial read.
# Forcing /dev/null guarantees immediate EOF regardless of how we're invoked.
set +e
codex exec \
    --sandbox workspace-write \
    --skip-git-repo-check \
    --cd "$WORK" \
    --output-last-message "$WORK/codex.lastmsg.log" \
    "$(cat "$PROMPT_FILE")" < /dev/null 2> "$LOG"
CODEX_RC=$?
set -e

# Happy path: the file is exactly where we asked.
if [[ -f "$OUT_PNG" ]]; then
    echo "ok: codex wrote $OUT_PNG directly (rc=$CODEX_RC)"
    exit 0
fi

# Fallback: extract session id from stderr ("session id: <uuid>")
SESSION_ID=$(grep -oE 'session id: [0-9a-f-]+' "$LOG" 2>/dev/null \
    | head -1 \
    | awk '{print $3}')

if [[ -z "$SESSION_ID" ]]; then
    echo "error: codex did not write $OUT_PNG and no session id in stderr" >&2
    echo "       see $LOG for diagnostics" >&2
    exit 1
fi

GEN_DIR="$HOME/.codex/generated_images/$SESSION_ID"
if [[ ! -d "$GEN_DIR" ]]; then
    echo "error: session dir not found: $GEN_DIR" >&2
    echo "       codex stderr:" >&2
    tail -20 "$LOG" >&2
    exit 1
fi

# Find the newest ig_*.png in the session dir.
GENERATED=$(find "$GEN_DIR" -name 'ig_*.png' -type f \
    -printf '%T@ %p\n' 2>/dev/null \
    | sort -nr \
    | head -1 \
    | awk '{print $2}')

if [[ -z "$GENERATED" || ! -f "$GENERATED" ]]; then
    echo "error: no ig_*.png in $GEN_DIR" >&2
    exit 1
fi

cp "$GENERATED" "$OUT_PNG"
echo "ok: recovered $GENERATED -> $OUT_PNG (codex rc=$CODEX_RC, fallback path)"
