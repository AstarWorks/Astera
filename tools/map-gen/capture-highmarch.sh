#!/usr/bin/env bash
# HighmarchInworldIT: real Paper import + one real MC client + full-slot disk comparison.
# Runs no Gradle tests/e2e suite. Own vfx-shot lock/lifecycle; never add an outer flock.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd -P)"
dest="$(realpath -m "${1:?usage: capture-highmarch.sh OUTPUT_DIR}")"
mkdir -p "$dest"
cat > "$dest/cameras-input.tsv" <<'EOF'
founder-view -124.2567 147.6235 -121.7852 -0.9233 62.2080
eye-level 0.5 65 -99.5 0 0
top-down 0 300 0 0 90
underside-low 90 24 210 155 -8
EOF
export SHOT_REAP_SCOPE=self
export SHOT_SERVER_MEM="${SHOT_SERVER_MEM:-2G}"
export SHOT_SERVER_VIEW="${SHOT_SERVER_VIEW:-20}"
export RENDER_DISTANCE="${RENDER_DISTANCE:-20}" FOV=70
bash "$root/tools/vfx-shot/map-shot.sh" "$dest/cameras-input.tsv" "$dest" annihilation-arena
python3 "$root/tools/map-gen/scan-world.py" "$dest"
