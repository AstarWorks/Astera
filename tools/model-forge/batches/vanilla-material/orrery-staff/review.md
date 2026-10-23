# Orrery staff — vanilla material review

| Part | Material |
|---|---|
| shaft | `wood.stripped_dark_oak_log`, Y grain and log ends |
| collars / emitter cap | `metal.iron_block` |
| emitter housing | `stone.polished_blackstone` |
| inset edges | `metal.copper_block` |
| cable loop | `cloth.black_wool` |
| front/back inset outward faces | custom orange glow-line atlas, `astera:blocks/orrery-staff-runes` |

All native faces use `material.apply`, seed 20261024, one texel per local model
unit. Only two glow faces use the whole custom 16×16 atlas. No Mojang PNG is
installed. All sprites share the block atlas. The 22.8-unit shaft splits at Y=18:
11 → 12 cuboids, with internal split faces omitted. Other coordinates, silhouette,
rotations and every display transform are retained.

Opened and reviewed `before.png`, `after.png`, `contexts.png`, `inventory.png`
and `sweep.png` (26 directions, camera metadata in `cameras.json`). The brown
shaft, pale fittings, dark head/cable and orange lines remain distinct. The shaft
has a subtle crop transition at its split, with no gap. Black wool provides a
quiet cable surface; no extra pattern was painted onto its thin faces.
`forge-contexts.png` retains the initial approximate-hand board; `contexts.png`
uses the player mannequin/perspective preview instead.

Critic: silhouette and part ratios preserved; no geometry redesign or concept
comparison is applicable to this material-only task. Original intersections are
retained. The inset is legible at 32/64px; at 16px the line reduces to an orange
accent. Existing GUI framing crops the top slightly; fixed-frame placement also
extends above the frame. These are inherited display issues, visible in the
player board, and need a separately authorized transform change.

Verifier: Forge reports zero errors, ten conservative overlap warnings and one
contact warning at the shaft split. The split has no internal caps. Measurement
retains the original bounds [5.96,2,6.098]–[13.46,31.88,9.902]. All vanilla face
UV densities are one; display transforms are byte-value equivalent. The 35-view
browser capture finished without browser errors. No Gradle, test runner, Docker,
E2E, production deployment or push was used.

## Native client

Opened and reviewed `client.png`: raw Xvfb root capture, 1280×720, portablemc
26.3 with llvmpipe, FOV 70, GUI scale 2, view bob off. The exact authored model
and glow PNG render on `minecraft:stick[minecraft:item_model="astera:orrery-staff"]`.
The shaft grain, iron fittings, black housing/cable and orange inscription are
visible; the native view fits the head and cable without the offline GUI crop.
No missing model, mixed-atlas or duplicate-sprite warnings appear for this pack.
Offline-launch Realms authentication and unavailable-audio-device errors are
unrelated to model loading.

The unique `build/orrery-staff-client` game directory used an isolated copy of a
closed creative superflat capture world. Its old display entities were removed
inside the copy before the final screenshot. The whole Xvfb launch/capture was
held under `/tmp/claude-1000/astera-mc-client.lock`; Xvfb selected a free display
with `-displayfd`. No dedicated server or live container was used.

Generate the minimal pack with `capture-pack.py --out <game-dir>/resourcepacks/vanilla-material`,
enable `file/vanilla-material`, then launch the installed portablemc runtime with
`--main-dir ~/.cache/astera-vfx-shot/mcdir start 26.3 --mc-dir <game-dir>` under Xvfb
and `LIBGL_ALWAYS_SOFTWARE=1 LP_NUM_THREADS=4`. In an isolated creative superflat,
give the item above and capture the root window with ImageMagick `import` without
resizing or painting it. `client.png` is that unedited screenshot.

## Reproduction

Save the original JSON from this commit's parent and run with a fresh project:

```sh
git show <parent>:content/models/items/orrery-staff.model.json > build/orrery-staff-before.json
python3 tools/model-forge/batches/vanilla-material/orrery-staff/remake.py \
  --before build/orrery-staff-before.json --project build/orrery-staff-replay
```

Rendering uses the local 26.3 client jar (`MODEL_FORGE_CLIENT_JAR` can override).
The player board needs the existing skill-viewer packages (`bun install
--frozen-lockfile` in `tools/skill-viewer`) and Chromium. `operations.json` records
Forge calls; `measure.json` and `validation.json` retain the final diagnostics.

## Catalog wishes / remaining risks

Optional wish: a quiet black rubber-like material for cable insulation; the
closest present entry, black wool, is used here. No catalog change is required.
Native animation, swing, FOV variants and the production pack build remain manager
verification items. The glow atlas is painted color, not emissive lighting.
