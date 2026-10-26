#!/usr/bin/env bash
# Regenerate everything in bastion-greatsword S1 that derives from the weapon rig, in order.
# Run after ANY change to content/vfx/bastion-s1-rig.yaml or to the skill's play_vfx / dash / freeze timing.
#   1. ring sprites (hring = hit 1, ring = hit 2) from the rig path
#   2. fx-high / fx-low YAML (contact anchors, tile spawns; drops the particle layer)
#   3. particle layer appended to fx-high / fx-low from the rig path
#   4. :tools:item-art:run (sprite png / font / meta from the sources)
#   5. restore the sprite PNGs / preview / .pyc that item-art re-encodes on this machine but did not change
# Usage (repo root): bash tools/item-art/scripts/bastion_s1_regen.sh
# Then: git status, commit the regenerated content, and only then capture (meta.json records HEAD).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
S=tools/item-art/scripts

python3 "$S/bastion_s1_sprites.py" hring ring
python3 "$S/bastion_s1_fx.py"
python3 "$S/bastion_s1_particles.py"
flock /tmp/claude-1000/astera-gradle.lock ./gradlew :tools:item-art:run -q

# keep only bastion-s1 sprite changes; everything else item-art touched is a re-encode
git diff --name-only -- content/textures/generated/sprites docs/assets \
  | grep -v '/bastion-s1-' \
  | xargs -r git restore --
git restore -- "$S/__pycache__" 2>/dev/null || true
git ls-files --others --exclude-standard -- "$S/__pycache__" | xargs -r rm -f

git status --short -- content tools/item-art

