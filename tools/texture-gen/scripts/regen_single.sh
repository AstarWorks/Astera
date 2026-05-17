#!/usr/bin/env bash
# regen_single.sh — regenerate a single 32×32 tile in place via Codex CLI.
#
# Usage:
#   regen_single.sh OUT_PNG REFINED_PROMPT
#
# Replaces OUT_PNG with a newly-generated, downscaled, quantized 32×32 PNG
# of a single subject described by REFINED_PROMPT. Used by the Web UI's
# /regenerate endpoint.
#
# Boundary: human / dev-tool only. Never invoked by Astera build/runtime.
# See ADR-0018 and tools/texture-gen/README.md.

set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 OUT_PNG REFINED_PROMPT" >&2
    exit 2
fi

OUT_PNG="$1"
REFINED="$2"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK=$(mktemp -d -t regen.XXXX)
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/prompt.txt" <<EOF
Use your image-generation tool to create a single PNG at $WORK/single.png.

Subject: $REFINED

Style requirements:
- Classic 32x32 pixel-art aesthetic look (we will downscale)
- Hard 1-pixel dark outline around the shape
- Limited palette: <=16 distinct colors total
- No anti-aliasing on edges, no semi-transparent pixels
- Bold silhouette readable at thumbnail size
- One subject centered (this is for ONE tile, not a grid)

Background pure black (we will key it out to transparency in post).

After generation, save the file to: $WORK/single.png
EOF

bash "$SCRIPT_DIR/codex_generate.sh" "$WORK/single.png" "$WORK/prompt.txt"

python3 "$SCRIPT_DIR/palette_quantize.py" "$WORK/single.png" \
    --colors 32 --size 32,32 --background remove --out "$WORK/final"

QUANTIZED=$(ls "$WORK/final"/*.png 2>/dev/null | head -1)
if [[ -z "$QUANTIZED" || ! -f "$QUANTIZED" ]]; then
    echo "error: quantize produced no output in $WORK/final/" >&2
    exit 1
fi

cp "$QUANTIZED" "$OUT_PNG"
echo "regenerated: $OUT_PNG"
