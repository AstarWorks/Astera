# Gossamer katana — restored palette (ADR-0251)

Palette authority: `36743b70:content/models/items/gossamer-katana.model.json`
and its original 128×128 atlas. `before.png` renders that historical model through
Forge. Geometry, face coverage and display authority: this commit's parent HEAD.

| Parts | Vanilla pattern | Tint |
|---|---|---|
| Blue underwrap | `cloth.white_wool` | `#1463b5` |
| Crossed silk / side returns | `cloth.white_wool` | `#f6686d` |
| Curved silver blade | `metal.iron_block` | `#cce4f4` |
| Cutting edge / web spokes | `metal.iron_block` | `#b9d0da` |
| Dark spine | `metal.iron_block` | `#293641` |
| Tsuba core | `metal.iron_block` | `#ffb638` |
| Rim, ferrules, pommel, pins, engraving | `metal.iron_block` | `#ffdc79` |

The historical atlas's silver is cool blue-gray, not white. Its underwrap is
navy/blue, crossed silk is coral/crimson, and **the tsuba core is gold**, despite
the first vanilla pass assigning anvil there. Light iron and white wool preserve
metal and fabric patterns while allowing those colours to survive multiplication.
Gold fittings contrast with the blue underwrap and red crossing, keeping this
thin curved katana recognizable among the other blades. No new textures.

## Critic and verifier

Opened `before.png`, `after.png`, `contexts.png`, `inventory.png`,
`player-contexts.png` and `sweep.png`. The latter covers 26 orbit directions;
player contexts cover both hands in first/third person, a side view, two ground
spins, fixed and GUI. The browser rendered all 35 views without errors.

- Silhouette/proportions: yes, all 148 elements' non-face fields are exactly HEAD.
  No splits, geometry edits, face removal, or display changes.
- Ornament/front/back/oblique readability: yes, the gold guard frames the red/blue
  grip; cool silver and dark spine survive opposite views. Thin edge-on blade
  visibility follows the original geometry.
- Palette/UVs: yes, seven constant tints; quiet aligned iron crops on blade sections
  retain the existing seam mitigation and one texel per model unit.
- Inventory: yes at 32/64px; at 16px the slender silhouette and gold/red grip read,
  while individual wrapping crosses and spokes merge into subpixel detail.
- Joins: original stepped blade edges and layered wrap remain. No claim of a new
  geometry/z-fighting audit; no separate concept image supplied for this recolour.
- Requested fix: recover historical part hues, including the gold tsuba, with light
  vanilla bases. Verifier: before/after boards show matching colour families and
  the same shape; neither reverse views nor player contexts show a new regression.

`verification.json` records exact geometry/display preservation and identical
Forge export/reimport. Validation has zero errors and unchanged conservative
warnings: 342 AABB overlaps, 19 possible coplanar contacts, one disconnected
component report. No unit/integration tests, Gradle, E2E, Docker, server or push.

## Reproduction

From this commit's parent, save the two independent inputs and use a fresh project:

```sh
git show 36743b70:content/models/items/gossamer-katana.model.json > /tmp/gossamer-original.json
git show <parent>:content/models/items/gossamer-katana.model.json > /tmp/gossamer-head.json
python3 tools/model-forge/batches/vanilla-material/gossamer-katana/remake.py \
  --project /tmp/gossamer-tint-forge --before /tmp/gossamer-original.json \
  --head /tmp/gossamer-head.json
flock /tmp/claude-1000/astera-mc-client.lock \
  python3 tools/model-forge/batches/vanilla-material/gossamer-katana/capture-client.py
```

The capture helper expects a fresh game/pack directory under
`build/gossamer-katana-client`. Create a creative superflat with commands enabled
in its UI; the helper applies capture commands after login. It uses portablemc
26.3, dynamic Xvfb display, llvmpipe, 1280×720, FOV 70, view bob off, GUI scale 2.
The minimal pack follows the builder: remove `astera_tints` from cuboid JSON and
emit all seven `minecraft:constant` sources in the item definition. Exact model
and tint-array equality with that extraction are checked. No vanilla PNGs copied.

## Remaining risks / catalog wishes

Existing stepped blade joins and overlapping ornaments remain visible, especially
at native first-person scale. Still captures do not cover swing/use animations,
alternate FOV or shader packs. Production pack/build/deployment verification stays
with manager CI. No catalog additions needed; wool remains coarser than silk at
close range, but supplies the required cloth pattern without a new asset.

## Native client verdict

Opened the final `client.png`: the blue-gray blade, dark spine, golden tsuba and
fittings, navy underwrap and coral crossings render in the real 26.3 client.
The centre stays clear; the current HEAD display places the lower grip below the
screen. Original blade steps are pronounced at this perspective, retained as
required. The raw screenshot is unedited 1280×720. This capture used a newly
created isolated creative superflat named `Gossamer Tint Review`, structures off,
commands on, peaceful/noon/clear. The whole launch, UI and screenshot session held
the shared flock. No mixed-atlas, missing-texture, model-loading, duplicate-sprite
or failed capture-command messages occurred. Offline-account Realms errors are
unrelated to this local singleplayer review.
