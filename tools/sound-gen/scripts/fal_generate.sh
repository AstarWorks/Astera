#!/usr/bin/env bash
# fal_generate.sh — invoke fal.ai Stable Audio 2.5 (text-to-audio) and
# download the result WAV to a target path.
#
# Usage:
#   fal_generate.sh PROMPT DURATION_SEC OUT_WAV
#
# Returns 0 on success with OUT_WAV written; non-zero otherwise.
#
# Boundary: human-run / skill-invoked only. Never invoked by Astera build
# or runtime. See tools/sound-gen/README.md and ADR-0018 / ADR-0026.

set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo "usage: $0 PROMPT DURATION_SEC OUT_WAV" >&2
    exit 2
fi

PROMPT="$1"
DURATION="$2"
OUT_WAV="$3"

if [[ -z "${FAL_KEY:-}" ]]; then
    echo "error: FAL_KEY not set; export it in your shell rc" >&2
    echo "       see tools/sound-gen/README.md" >&2
    exit 1
fi

for bin in curl jq; do
    if ! command -v "$bin" >/dev/null 2>&1; then
        echo "error: required CLI not on PATH: $bin" >&2
        exit 1
    fi
done

WORK=$(dirname "$OUT_WAV")
mkdir -p "$WORK"
LOG="$WORK/fal.stderr.log"
: > "$LOG"

QUEUE_BASE="https://queue.fal.run/fal-ai/stable-audio-25/text-to-audio"

# Round duration up to nearest integer second (Stable Audio takes
# `seconds_total` as integer; sub-second is post-trimmed in to_ogg.sh).
DUR_INT=$(awk -v d="$DURATION" 'BEGIN {
    n = int(d);
    if (d > n) n = n + 1;
    if (n < 1) n = 1;
    print n
}')

echo "[fal.ai] prompt: $PROMPT" >&2
echo "[fal.ai] seconds_total: $DUR_INT (rounded from $DURATION)" >&2
echo "[fal.ai] estimated cost: \$0.20 (flat per generation)" >&2

# 1. Submit job to queue endpoint.
SUBMIT_BODY=$(jq -n \
    --arg prompt "$PROMPT" \
    --argjson seconds_total "$DUR_INT" \
    '{prompt: $prompt, seconds_total: $seconds_total}')

SUBMIT_RESP=$(curl -sS --max-time 30 \
    -X POST "$QUEUE_BASE" \
    -H "Authorization: Key $FAL_KEY" \
    -H "Content-Type: application/json" \
    -d "$SUBMIT_BODY" 2>>"$LOG") || {
    echo "error: fal.ai queue submit failed; see $LOG" >&2
    exit 1
}

REQUEST_ID=$(echo "$SUBMIT_RESP" | jq -r '.request_id // empty')
STATUS_URL=$(echo "$SUBMIT_RESP" | jq -r '.status_url // empty')
RESPONSE_URL=$(echo "$SUBMIT_RESP" | jq -r '.response_url // empty')

if [[ -z "$REQUEST_ID" ]]; then
    echo "error: fal.ai response missing request_id" >&2
    echo "       response: $SUBMIT_RESP" >&2
    exit 1
fi

echo "[fal.ai] request_id: $REQUEST_ID" >&2

# 2. Poll status until COMPLETED.
MAX_WAIT=120
ELAPSED=0
SLEEP=2
while (( ELAPSED < MAX_WAIT )); do
    STATUS_RESP=$(curl -sS --max-time 10 \
        -H "Authorization: Key $FAL_KEY" \
        "$STATUS_URL" 2>>"$LOG") || {
        echo "warn: status poll error; retrying in ${SLEEP}s" >&2
        sleep "$SLEEP"
        ELAPSED=$((ELAPSED + SLEEP))
        continue
    }
    STATUS=$(echo "$STATUS_RESP" | jq -r '.status // empty')

    case "$STATUS" in
        COMPLETED)
            break
            ;;
        IN_QUEUE|IN_PROGRESS)
            sleep "$SLEEP"
            ELAPSED=$((ELAPSED + SLEEP))
            ;;
        FAILED|CANCELED|"")
            echo "error: fal.ai job status=$STATUS" >&2
            echo "       response: $STATUS_RESP" >&2
            exit 1
            ;;
        *)
            echo "warn: unexpected status=$STATUS, treating as in-progress" >&2
            sleep "$SLEEP"
            ELAPSED=$((ELAPSED + SLEEP))
            ;;
    esac
done

if [[ "$STATUS" != "COMPLETED" ]]; then
    echo "error: fal.ai job did not complete within ${MAX_WAIT}s" >&2
    exit 1
fi

# 3. Fetch result; extract audio URL.
RESULT_RESP=$(curl -sS --max-time 10 \
    -H "Authorization: Key $FAL_KEY" \
    "$RESPONSE_URL" 2>>"$LOG") || {
    echo "error: fal.ai result fetch failed; see $LOG" >&2
    exit 1
}

AUDIO_URL=$(echo "$RESULT_RESP" | jq -r '.audio.url // empty')
if [[ -z "$AUDIO_URL" ]]; then
    echo "error: result missing .audio.url" >&2
    echo "       response: $RESULT_RESP" >&2
    exit 1
fi

# 4. Download the audio file. fal.ai returns WAV (content_type: audio/wav).
curl -sS --max-time 60 -L "$AUDIO_URL" -o "$OUT_WAV" 2>>"$LOG" || {
    echo "error: audio download failed; see $LOG" >&2
    exit 1
}

# Sanity-check: file is a RIFF WAV (or at least non-empty audio).
if [[ ! -s "$OUT_WAV" ]]; then
    echo "error: downloaded file empty: $OUT_WAV" >&2
    exit 1
fi

SIZE=$(stat -c '%s' "$OUT_WAV" 2>/dev/null || stat -f '%z' "$OUT_WAV")
echo "ok: fal.ai → $OUT_WAV ($SIZE bytes)"
