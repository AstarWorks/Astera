#!/usr/bin/env bash
# dry_run.sh — produce a sine-wave test WAV without hitting fal.ai.
#
# Used to smoke-test the to_ogg.sh post-process pipeline without
# burning API credits or requiring FAL_KEY. The output is intentionally
# uninteresting (pure 440 Hz tone) — quality QA tests should reject it.
#
# Usage:
#   dry_run.sh DURATION_SEC OUT_WAV
#
# Falls back through preference: sox → ffmpeg sine source. One of the two
# must be on PATH.

set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 DURATION_SEC OUT_WAV" >&2
    exit 2
fi

DURATION="$1"
OUT_WAV="$2"

mkdir -p "$(dirname "$OUT_WAV")"

if command -v sox >/dev/null 2>&1; then
    # sox: cleanest, smallest call.
    sox -n -r 44100 -c 1 "$OUT_WAV" synth "$DURATION" sine 440 vol 0.5
    echo "ok: sox → $OUT_WAV (${DURATION}s, 440 Hz)"
elif command -v ffmpeg >/dev/null 2>&1; then
    # ffmpeg sine source. -t accepts seconds (decimal OK).
    ffmpeg -hide_banner -loglevel error -y \
        -f lavfi -i "sine=frequency=440:sample_rate=44100" \
        -t "$DURATION" \
        -ac 1 \
        "$OUT_WAV"
    echo "ok: ffmpeg sine → $OUT_WAV (${DURATION}s, 440 Hz)"
else
    echo "error: neither sox nor ffmpeg on PATH" >&2
    echo "       install one: apt install sox  /  brew install sox" >&2
    exit 1
fi
