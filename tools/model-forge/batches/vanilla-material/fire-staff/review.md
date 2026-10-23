# Fire staff — extended vanilla material redo

| Parts | Material |
| --- | --- |
| Flame contour and curl point | `concrete.orange_concrete` |
| Front/back outer flame core and glints | `concrete.yellow_concrete` |
| Ten innermost core facets | custom `astera:blocks/fire-staff-glow` (16×16 yellow-white glow line) |
| Socket and pommel rubies | `gem.redstone_block` |
| Shaft and spiral wood | `wood.stripped_dark_oak_log` (retained) |
| Grip and raised wraps | `cloth.brown_wool` (retained) |
| Fork, cradle, collars, engraving and pommel facets | `metal.gold_block` (retained) |

The first pass's copper silhouette and gold core read as dull terracotta/metal.
The redo replaces those flame materials with saturated orange and yellow; ruby
accents now use redstone instead of wool. A small authored glow atlas raises the inner core to pale yellow-white.
Catalog wishes: none for this redo. Materials remain static and do not emit light.
The custom atlas stays under `blocks/`, sharing the native block atlas; no Mojang PNGs are installed.

## Critic and verifier

Opened and reviewed `before.png`, `after.png`, `contexts.png`,
`player-contexts.jpg`, `sweep.png` (26 directions), and `inventory.png`.
The original from `36743b70` is a regression reference; no concept was supplied.
The proposed fix was limited to orange flame contours/curl, yellow core/glints,
and redstone rubies (41 elements). The first native capture showed that client
lighting darkened the concrete-only center; a second fix adds a pale glow line to
the ten inner core facets, leaving the yellow outer core in place. The eight-view and player-context boards
show stronger orange/yellow separation than the copper first pass. Front and
back cores stay distinct from the gold cradle, and oblique/side views retain
an orange silhouette. At 16px the hot head reads as a compact orange/yellow
mark; at 32/64px the inner flame is clearer. Tiny ruby details merge at 16px.
No new visible material seam, missing face or hand-context regression was seen.

Compared with the first pass, all 114 elements retain exactly the same geometry,
rotations and display transforms. Vanilla UV coordinates remain unchanged; the
ten custom glow facets use their whole 16×16 atlas to show the bright center line. Wood, grip and gold
fittings are unchanged. Relative to the original 113-element model, only the
previously recorded 20-unit shaft split remains; its internal faces are omitted.
One vanilla texel per local model unit, deterministic seed 20261023.
`measure.json` records zero validation errors, 201 conservative overlap candidates,
3 possible coplanar contacts and 1 disconnected-component report, unchanged from
the first pass. These inherited intersections are not a complete z-fighting audit.
A fresh replay reproduced the installed model and Forge boards byte-for-byte.

## Reproduction

```sh
git show 36743b70:content/models/items/fire-staff.model.json > build/fire-staff-original.json
python3 tools/model-forge/batches/vanilla-material/fire-staff/remake.py --project build/fire-staff-fresh --before build/fire-staff-original.json
python3 tools/model-forge/context_preview.py --out build/fire-staff-contexts fire-staff
python3 tools/model-forge/context_preview.py --viewer --out build/fire-staff-viewer fire-staff
bun tools/model-forge/review.mjs build/fire-staff-viewer/viewer.html build/fire-staff-review fire-staff
python3 tools/model-forge/batches/vanilla-material/fire-staff/capture-pack.py --out build/fire-staff-client/resourcepacks/vanilla-material
flock /tmp/claude-1000/astera-mc-client.lock python3 tools/model-forge/batches/vanilla-material/fire-staff/capture-client.py
```

Use fresh project/pack directories. Native capture requires an isolated creative
superflat named `Fire Staff Materials` in `build/fire-staff-client/saves/` with
commands enabled. The capture script uses the installed portablemc 26.3 runtime,
unique Xvfb display, software GL, FOV 70, view bob off and GUI scale 2. It removes
old display entities/inventory and gives the exact authored `astera:fire-staff`
model on an iron axe. Screenshot is an unedited 1280×720 Xvfb root capture.

## Native client verification

Opened the final `client.png`: native 26.3, Xvfb/llvmpipe, 1280×720.
The pale inner glow now stays distinct from the darker yellow-gold fittings;
the orange contour, yellow outer core and red socket gem render correctly.
The hot center remains visible in the native hotbar icon. Client lighting still
darkens the orange compared with the offline board. No missing-model/texture,
mixed-atlas or duplicate-sprite diagnostics occurred. Offline authentication,
unavailable audio and an unrelated post-effect warning do not affect this render.
The installed minimal-pack model and glow PNG match the committed sources exactly.

Both native sessions held the shared client lock for their entire lifetime.
The unique game directory is `build/fire-staff-client`, display dynamically
assigned `:0`. The creative superflat was copied from the previous isolated
thunder-staff capture, with inventory and display entity cleared before capture.
The screenshot is raw and unedited; production pack/server deployment is unverified.

## Remaining risks

Static flame colors do not animate or emit light. Existing bevel intersections
and ornament gaps remain. Offline previews approximate client lighting/cameras.
Production pack compilation and server deployment remain manager responsibilities.
No Gradle, tests, E2E, Docker, standalone server or push was run.
