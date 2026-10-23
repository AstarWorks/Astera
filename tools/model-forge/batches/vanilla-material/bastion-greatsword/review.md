# Vanilla materials — bastion-greatsword

The original golden ceremonial greatsword keeps its cuboids, silhouette, rotations
and every display transform. Forge splits the 18-unit blade and six long bevels
into external-face-only cells: 105 → 112 elements. No custom PNG is needed.

| Parts | Catalog material |
|---|---|
| Blade, taper, guard, shield plates, bands, chevrons, studs and engraved dashes | `metal.gold_block` |
| Edge/crown bevels, pommel seat and glint | `metal.iron_block` |
| Fuller rails and shield insets | `metal.anvil` |
| Black grip and leather wraps | `cloth.black_wool` |

All 656 surviving faces use one vanilla texel per local model unit, including
fractional spans. Crops use seed 20261023. All references, including particle,
use `minecraft:block/`; no Mojang PNG or custom atlas is shipped. Black wool is
the closest catalog surface for the original black leather grip. Catalog wishes:
none required for this pass.

## Review

- Opened `before.png`, `after.png` and `contexts.png`: matching eight-view model
  boards and seven authored display contexts. The golden body, silver cutting
  edge and dark grip remain distinct. Gold block's horizontal bands are visible
  around the upper blade/split; they read as native material variation. Thin
  detail uses fractional texels, without dense noisy patterns or stretched UVs.
- Opened `player-contexts.png`: perspective player mannequin, both hands in
  first/third person, third-person side, two ground spins, frame and GUI. Native
  hand signs are included. The sword sits in the grip and leaves the center clear.
- Opened the separate 26-direction reviewer sweep in the ignored build directory;
  no missing faces or new silhouette defects were visible. This is a finite sweep,
  not a complete hidden-face/z-fighting audit. No external concept was supplied
  for this material-only task; the original model is the geometry baseline.
- Opened `inventory.png`: nearest-neighbor 16/32/64px GUI review. Blade, crossguard
  and black grip remain readable; tiny dashes are intentionally subpixel at 16px.
- `geometry.json` records unchanged bounds, 1 texel/unit density and zero validation
  errors. Conservative warnings change from 187 overlaps / 13 possible coplanar
  contacts / 1 disconnected-component report to 203 / 20 / 1. The seven additional
  contacts follow the seven splits; internal split faces are omitted. Original
  decorative intersections and separations remain intentionally untouched.

## Real 26.3 client

Opened `client.png`, an unmodified 1280×720 Xvfb root screenshot from the installed
portablemc 26.3 runtime with llvmpipe (`LIBGL_ALWAYS_SOFTWARE=1`,
`LP_NUM_THREADS=4`). The whole launch/capture/exit held
`flock /tmp/claude-1000/astera-mc-client.lock`; Xvfb chose a free display with
`-displayfd`. The dedicated game directory was `build/bastion-greatsword-client`.
A new creative superflat, structures off and commands on, was created locally.
FOV 70, GUI scale 2, view bob off; peaceful, clear weather, time 6000;
player at (0.5, -60, 0.5), yaw/pitch 0/0. Item command:

```mcfunction
/give @s minecraft:iron_sword[minecraft:item_model="astera:bastion-greatsword"]
```

The minimal capture pack contains the exact authored model and item definition,
plus the builder-equivalent `blocks/` atlas directory source. No vanilla pixels
are copied. Native gold, silver edge, dark shield inset and grip render correctly;
the upper gold bands are visible and the center remains clear. The native held
sword is larger/darker than the offline preview, and the grip extends below the
screen according to the unchanged original display transform. There is no
missing-model cube, mixed-atlas error, missing-resource report or duplicate-sprite
warning in the capture log. Offline-login Realms and unavailable sound-device
errors are unrelated to the model. The world was saved and the client exited
before releasing the shared lock. This verifies a minimal local capture pack,
not a production-built pack or Astera deployment.

## Reproduction

Save the source from the parent of this commit, then run from the repository root:

```sh
git show <parent>:content/models/items/bastion-greatsword.model.json > build/bastion-before.json
python3 tools/model-forge/batches/vanilla-material/bastion-greatsword/remake.py \
  --project build/bastion-material-replay --before build/bastion-before.json
python3 tools/model-forge/context_preview.py --out build/bastion-player-contexts bastion-greatsword
python3 tools/model-forge/batches/vanilla-material/bastion-greatsword/capture-pack.py \
  --out build/bastion-greatsword-client/resourcepacks/vanilla-material
```

Use fresh output/project/pack directories. The remake asserts unchanged bounds,
unchanged display transforms, correct density and valid final content. It writes
only the authorized model and its local batch evidence. The operation log records
the original import, checkpoint, material batch, packaging reimport and final diff.
A second fresh-project replay produced byte-identical exported model JSON.

## Remaining limits

Production pack compilation and manager CI integration checks were not run.
No Gradle, test runner, E2E, Docker, external server or push was used. Offline
lighting, approximate hands and static views do not establish animation/FOV/glint
behavior in every native context. Pre-existing bevel overlaps remain; native gold
block border bands and wool-for-leather appearance are deliberate material limits.
