# Vanilla materials — dagger

All 100 cuboids, names, geometry, silhouette and display transforms are unchanged.
Only materials/UVs change. No custom atlas or Mojang PNG is installed or committed.

| Parts | Catalog material |
|---|---|
| Blade sections, edge bevels, central ridges, grinding marks | `metal.iron_block` |
| Guard, grip collars, pommel facets | `metal.anvil` |
| Leather grip and lap bands | `cloth.brown_wool` |
| Leather stitches | `cloth.gray_wool` |
| Grip pins | `metal.gold_block` |

`material.apply` uses seed 20261023. All 600 faces retain one texel per model unit,
including fractions (measured range 0.9999999999999941–1.000000000000005). No splits
are needed. Independent initial blade crops looked mottled; a horizontal shared
crop introduced bands. Final north/south blade faces share a continuous,
quarter-turned longitudinal iron crop via `uv.set`, removing both distractions.

Opened and reviewed: `before.png`, `after.png` (same eight cameras), `contexts.png`
(Forge), `player-contexts.jpg` (perspective, both hands, player rest pose, ground,
frame, GUI), `angles.jpg` (26 directions; `cameras.json`) and `inventory.png`
(16/32/64px enlarged with nearest-neighbor sampling). The stepped edge, bright
blade, dark fittings and warm wrap remain readable. Both hands leave screen center
clear; grip, ground pivot and frame position retain their original transforms.
At 16px, tiny stitches/grinding marks disappear while the main groups remain clear.

Validation has zero errors. The original 225 AABB overlap reports, one possible
coplanar contact and one disconnected-component report remain unchanged. Top,
bottom and oblique images show no new visible gap or fighting seam; retained
construction overlaps are not a complete geometric or animation audit.

`client.png`: raw 1280×720 real 26.3 portablemc/Xvfb/llvmpipe capture, isolated
creative superflat, FOV 70, view bob off, GUI scale 2, exact authored model and
minimal pack, holding `minecraft:iron_sword[minecraft:item_model="astera:dagger"]`.
Launch through capture holds `/tmp/claude-1000/astera-mc-client.lock`; Xvfb picks a
unique display via `-displayfd`. Game directory: `build/dagger/client-dagger`.
The pack references vanilla block sprites directly; it contains no vanilla PNG.
Opened the native capture: blade, dark fittings, wrap and gold pins resolve correctly.
Small grinding-mark highlights remain visible on the blade. The log has no missing
model/texture, mixed-atlas or duplicate-sprite messages (`client-log.txt`).

Reproduce from the repository root with fresh output directories:

```sh
git show HEAD^:content/models/items/dagger.model.json > /tmp/dagger-before.json
python3 tools/model-forge/batches/vanilla-material/dagger/remake.py \
  --project build/dagger-reproduction --before /tmp/dagger-before.json
python3 tools/model-forge/batches/vanilla-material/dagger/capture-pack.py \
  --out build/dagger-client/resourcepacks/vanilla-material
```

For the orbit sweep use `context_preview.py --viewer` then `review.mjs`, as in the
Forge README. The embedded-pixel HTML stays ignored/local. The native capture uses
the pilot's installed runtime, enables `file/vanilla-material`, creates a creative
superflat with commands, gives the item above and captures the Xvfb root unedited.

Remaining risks/catalog wish: the catalog lacks quiet dark leather, so brown wool
is the closest cloth entry. Existing bevel overlaps remain. Native capture checks
the held model; combat animations, other FOVs and shader packs are not covered.
Production pack/deployment and integration checks remain manager/CI work.
No Gradle, tests, E2E, Docker, external server or push was run.
