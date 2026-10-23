# Edict blade — original palette restored with vanilla patterns

The original at `36743b70` is a pair of cool-white gloves, muted brass cuffs and
lighter brass chain links. `before.png` renders that revision with its unchanged
original texture. The remake imports the current model separately, preserving
HEAD's 15 cuboids, rotations, face coverage, other metadata and entire display block.

| Parts | Vanilla pattern | Tint | Original sampled RGB |
|---|---|---|---|
| white-glove-0/1 | cloth.white_wool | #f2f6fc | #f2f6fc |
| finger-* | cloth.white_wool | #d6deec | #d6deec |
| brass-cuff-0/1 | metal.iron_block | #aba175 | #938a65 |
| chain-0 through chain-4 | metal.iron_block | #e5dcae | #c5bd96 |

Metal tints compensate for the light iron base's multiplicative darkening. The
cuffs retain the original olive brass hue; the chain is visibly lighter. Wool
keeps the cloth pattern and cool-white identity. Coplanar finger up/north faces
share glove UVs and tint to avoid speckling; the exposed finger fronts retain the
slightly darker blue-white tint. All faces keep one vanilla texel per local unit.
There are no new custom textures or catalog wishes.

Opened and reviewed `before.png`, `after.png`, `material-comparison.png`,
`contexts.png`, `contexts-player.png`, `inventory.png`, and `sweep.png`.
Eight canonical views and the 26-direction sweep retain the silhouette, finger
steps and original separated chain assemblies. The final comparison is much
closer to the original than saturated gold or pink/green copper. The 16/32/64px
GUI reductions retain the white/brass pair; individual links become subpixel at
16px. Both hands, ground/spin and fixed views use the current display transforms.
The original model is regression evidence; no independent concept was supplied.

Verification: geometry/display/face coverage and metadata match HEAD; export and
reimport match, and no texture PNGs are exported. Forge has zero errors, with the
same 14 overlap and one disconnected-component warnings as before. Existing
intentional intersections remain. See `operations.json` and `verification.json`.

## Native capture and reproduction

`capture-pack.py` implements the current builder mapping: remove `astera_tints`
from the cuboid JSON, and emit matching `minecraft:constant` sources in the item
definition's `model.tints`. The pack otherwise retains the exact authored model,
uses only block-atlas resources and contains no vanilla PNGs. This is a minimal
capture pack, not a production-built or deployed ZIP.

```sh
git show 36743b70:content/models/items/edict-blade.model.json > build/edict-original.json
python3 tools/model-forge/batches/vanilla-material/edict-blade/remake.py \
  --project build/edict-replay --before build/edict-original.json
python3 tools/model-forge/batches/vanilla-material/edict-blade/capture-pack.py \
  --out build/edict-blade-client/resourcepacks/vanilla-material
flock /tmp/claude-1000/astera-mc-client.lock \
  python3 tools/model-forge/batches/vanilla-material/edict-blade/capture-client.py \
  --world-source /path/to/isolated-26.3-creative-superflat
```

Use fresh project/pack directories. The capture uses portablemc 26.3 and a unique
Xvfb display, llvmpipe, 1280x720, FOV 70, bob off, GUI scale 2. Its isolated copy is
`build/edict-blade-client/saves/Edict Blade Materials`. `client.png` is a native
first-person screenshot; `client-detail.png` adds an item_display in the authored
GUI context at entity scale [3,3,3], translation [0,0.4,0]. Neither image is edited.
Both screenshots were opened and reviewed: the current first-person placement
shows the complete pair at lower right, and the detail view confirms the muted
brass tint, lighter chain and white cloth with native vanilla patterns. The hotbar
icon is readable. There are no missing model/texture, mixed-atlas, duplicate-sprite
or model-bake messages. Offline account/Realms authentication, unavailable OpenAL
and an unrelated end_of_frame post-effect warning appear in the client log.

Remaining limits: small chain details vanish at inventory scale; multiplication
cannot preserve the original flat colour on every textured pixel. Existing
coincident faces and separate chain assemblies remain. Alternate FOV, animation,
shaders, production pack compilation and deployment are unverified. No Gradle,
unit tests, check/test tasks, E2E, Docker, live containers or push was run.
