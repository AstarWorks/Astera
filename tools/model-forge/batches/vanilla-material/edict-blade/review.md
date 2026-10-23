# Extended vanilla materials — edict-blade

The authored item is two white gloves with brass/khaki cuffs and five chain links.
This redo replaces the first pass's bright gold with muted exposed-copper patina.
All 15 cuboids, rotations, face coverage and display transforms match the ORIGINAL
model at `36743b70`; `before.png` is rendered from that revision, not the gold pass.

| Parts | Material | UV choice |
|---|---|---|
| white-glove-0/1, finger-* | cloth.white_wool | Existing deterministic crops and matching coplanar finger UVs retained. |
| brass-cuff-0/1 | metal.exposed_copper | Side crops start at (1,10); top/bottom at (8,6), retaining one texel per unit. |
| chain-0 through chain-4 | metal.exposed_copper | Small khaki crop at (1,2), retaining one texel per unit. |

Opened `material-comparison.png`: identical front/oblique cameras show the original,
gold, raw gold, exposed copper, weathered copper, red terracotta, brown mushroom and
selected copper crops side by side. Raw gold remains yellow/orange; weathered copper
is too green; red terracotta is too red; mushroom is browner and reads as leather.
Selected exposed-copper crops preserve the original olive/khaki family while retaining
subtle brown/green patina. Uncropped exposed copper has excessive pink patches.
Yellow terracotta is not in this catalog; no shared catalog change was made.

Opened `after.png`, `contexts.png`, `contexts-player.png`, `sweep.png`, `inventory.png`
and both new native client screenshots. The eight canonical directions and 26-angle
perspective sweep retain the outline and finger steps. Patina remains restrained in GUI,
third person, ground and fixed views. At 16px the chain is subpixel; the white/khaki pair
still reads. No new seams, stretched texels or missing textures were visible. The
pre-existing finger/glove coincident faces still share UVs to avoid speckling.
No concept image was supplied; the original is regression evidence only, not a claim
of matching an independently generated concept.

Geometry/display/face-coverage comparisons pass. Forge validation has zero errors,
14 conservative overlap warnings and one disconnected-components warning, unchanged
from the original. All 90 faces retain one vanilla texel per local model unit.
No custom or Mojang PNG is shipped, no geometry is split, and only block-atlas
resources are used. The original overlap and chain separation are intentionally retained.

## Native client

`client.png` and `client-detail.png` are raw 1280×720 Xvfb captures from Minecraft
Java 26.3 with portablemc/llvmpipe, FOV 70, view bob off and GUI scale 2. The entire
launch/capture holds `/tmp/claude-1000/astera-mc-client.lock`; Xvfb chooses a free display.
The isolated creative superflat is a local copy of the pilot-style Fire Staff Materials
capture world, under this worktree's `build/edict-blade-client/saves/Edict Blade Materials`.
The minimal pack contains the exact authored JSON and native block references.

The inherited first-person placement still clips almost the entire item below the
viewport; `client.png` shows the glove at the bottom right and the complete hotbar icon.
`client-detail.png` additionally presents the exact model in a native item_display using
its GUI context, entity scale [3,3,3] and translation [0,0.4,0]. This makes the muted cuffs
visible without editing authored transforms. Final logs have no missing texture/model,
mixed-atlas, model-bake or duplicate-sprite warnings. Offline authentication/Realms
messages are unrelated to the capture world.

## Reproduction

From the repository root, use fresh project/pack directories:

```sh
mkdir -p build/edict-redo
git show 36743b70:content/models/items/edict-blade.model.json > build/edict-redo/original.json
python3 tools/model-forge/batches/vanilla-material/edict-blade/remake.py \
  --project build/edict-redo/replay --before build/edict-redo/original.json
python3 tools/model-forge/batches/vanilla-material/edict-blade/compare-materials.py \
  --project build/edict-redo/comparison --before build/edict-redo/original.json
python3 tools/model-forge/context_preview.py --out build/edict-contexts edict-blade
python3 tools/model-forge/context_preview.py --viewer --out build/edict-viewer edict-blade
bun tools/model-forge/review.mjs build/edict-viewer/viewer.html build/edict-sweep edict-blade
python3 tools/model-forge/batches/vanilla-material/edict-blade/capture-pack.py \
  --out build/edict-blade-client/resourcepacks/vanilla-material
# Prepare the isolated creative capture world at the path above, then:
flock /tmp/claude-1000/astera-mc-client.lock \
  python3 tools/model-forge/batches/vanilla-material/edict-blade/capture-client.py
```

## Remaining risks / catalog wishes

- The preserved first-person transforms clip the item below the screen. Correcting
  placement needs a separate display-transform task.
- Exposed copper is a close khaki substitute with some brown/green patina; a dedicated
  low-contrast brass texture would match the original flat tone more precisely.
- Original overlaps and separated chain assemblies remain; animation, alternate FOV,
  shaders, production resource-pack compilation and deployment are unverified.
- No Gradle, unit tests, check/test tasks, E2E, Docker, servers or push was run.
