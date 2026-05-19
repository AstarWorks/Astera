#!/usr/bin/env bash
# to_ogg.sh — post-process a WAV into a Minecraft-ready OGG Vorbis.
#
# Pipeline:
#   input WAV
#     → mono (-ac 1)
#     → 44.1 kHz (-ar 44100)
#     → EBU R128 loudness normalize (loudnorm I=-16 TP=-1.5 LRA=11)
#     → OGG Vorbis (-c:a libvorbis -q:a 5)  (≈ 96 kbps)
#   output OGG
#
# Usage:
#   to_ogg.sh IN_WAV OUT_OGG
#
# Boundary: human-run / skill-invoked only. Never invoked by Astera build
# or runtime. See tools/sound-gen/README.md and ADR-0018.

set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 IN_WAV OUT_OGG" >&2
    exit 2
fi

IN_WAV="$1"
OUT_OGG="$2"

if [[ ! -f "$IN_WAV" ]]; then
    echo "error: input not found: $IN_WAV" >&2
    exit 1
fi

for bin in ffmpeg ffprobe; do
    if ! command -v "$bin" >/dev/null 2>&1; then
        echo "error: $bin not on PATH; install ffmpeg ≥4.2" >&2
        echo "       Debian: apt install ffmpeg" >&2
        echo "       macOS:  brew install ffmpeg" >&2
        exit 1
    fi
done

# Quick check: loudnorm filter must be present (ffmpeg ≥4.2).
# Capture into a var first; piping into `grep -q` triggers SIGPIPE on
# ffmpeg under `set -o pipefail`, which would fail the whole script.
FFMPEG_FILTERS=$(ffmpeg -hide_banner -filters 2>/dev/null || true)
if ! grep -qE '(^|[[:space:]])loudnorm([[:space:]]|$)' <<<"$FFMPEG_FILTERS"; then
    echo "error: ffmpeg is missing the 'loudnorm' filter (need ≥4.2)" >&2
    exit 1
fi
unset FFMPEG_FILTERS

mkdir -p "$(dirname "$OUT_OGG")"

# Transcode. We intentionally use single-pass loudnorm — it's slightly less
# accurate than two-pass but adequate for short SFX (<5s) and 5× faster.
# For long files (BGM, ambient ≥30s), revisit with two-pass.
ffmpeg -hide_banner -loglevel error -y \
    -i "$IN_WAV" \
    -ac 1 \
    -ar 44100 \
    -af "loudnorm=I=-16:TP=-1.5:LRA=11" \
    -c:a libvorbis \
    -q:a 5 \
    "$OUT_OGG"

# Verify the output. Refuse to claim success if any of these are off.
PROBE=$(ffprobe -v error -print_format json -show_streams "$OUT_OGG")
CODEC=$(echo "$PROBE" | grep -oE '"codec_name":[[:space:]]*"[^"]*"' | head -1 | sed 's/.*"\([^"]*\)"$/\1/')
CHANNELS=$(echo "$PROBE" | grep -oE '"channels":[[:space:]]*[0-9]+' | head -1 | grep -oE '[0-9]+$')
RATE=$(echo "$PROBE" | grep -oE '"sample_rate":[[:space:]]*"[^"]*"' | head -1 | sed 's/.*"\([^"]*\)"$/\1/')

if [[ "$CODEC" != "vorbis" || "$CHANNELS" != "1" || "$RATE" != "44100" ]]; then
    echo "error: output failed format assertion" >&2
    echo "  codec=$CODEC (expect vorbis)" >&2
    echo "  channels=$CHANNELS (expect 1)" >&2
    echo "  sample_rate=$RATE (expect 44100)" >&2
    rm -f "$OUT_OGG"
    exit 1
fi

SIZE=$(stat -c '%s' "$OUT_OGG" 2>/dev/null || stat -f '%z' "$OUT_OGG")
echo "ok: $IN_WAV → $OUT_OGG (mono 44.1kHz vorbis, $SIZE bytes)"
