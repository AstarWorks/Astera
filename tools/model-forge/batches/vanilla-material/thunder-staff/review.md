# thunder-staff material review

Geometry and display transforms are retained. Forge tiles the 20-unit wooden shaft at
Y=16 (146 → 147 elements); all other cuboids match within 1e-12 floating-point rounding.
All 878 external faces use one vanilla texel per local model unit. No custom texture or
Mojang PNG is added. The old item atlas is unused; all final references use the block atlas.

| Parts | Catalog material |
|---|---|
| Wooden shaft, spiral ridges/returns | `wood.stripped_dark_oak_log` (Y-axis grain; end caps use log tops) |
| Leather grip and raised wraps | `cloth.brown_wool` |
| Collars, prongs, studs, bezel, lightning strokes/tips | `metal.gold_block` |
| Orb facets, crystal pommel, amethyst sockets, prong insets | `gem.amethyst_block` |
| Bolt mounting posts | `metal.iron_block` |
| Three orb glints | `gem.diamond_block` |

Opened and reviewed `before.png`, `after.png`, `contexts-forge.png`, `contexts.png`,
`angles.png` and `inventory.png`. The eight canonical views retain the outline. The
26-direction sweep shows gold lightning distinct from the purple orb, readable prongs,
and a dark shaft with restrained grain. The shaft split does not show a conspicuous seam.
Both first-person hands leave the center clear; third-person grip, ground and frame
placement retain the original transforms. At 16px the lightning merges into the head;
at 32/64px it becomes readable. No new visible join defect was found in these views.
There is no supplied concept image for a concept-fidelity comparison.

`operations.json` records import, scene/element inspection, snapshot, batch material
assignments (seed 20261023), packaging re-import, render, measure, validation, diff and
export. A fresh replay produced identical model SHA256. `validation.json` records zero
errors and 306 conservative overlap warnings / 1 shaft contact / 1 disconnected-component
report (before: 304 overlaps / 0 contacts / 1 component report). Existing overlapping orb
facets, bevels and ornament are retained. The split has no internal end faces. The
intermediate mixed-atlas warning comes from the unused original slot; packaging removes it.

`client.png` was opened and reviewed: native Minecraft Java 26.3, portablemc,
Xvfb-selected display, llvmpipe, 1280×720, FOV 70, bob off, GUI scale 2. The exact
model is held as `minecraft:iron_axe[minecraft:item_model="astera:thunder-staff"]`.
The gold bolt remains distinct on the shaded purple orb; the shaft and grip read
as separate materials and the center is clear. The model has no missing-texture
faces. The log has no missing-model/texture, mixed-atlas or duplicate-sprite errors.

The entire client launch and root-window capture ran under
`flock /tmp/claude-1000/astera-mc-client.lock`. The game directory is
`build/thunder-staff-client`; its independent creative/peaceful superflat save was
copied from the existing capture baseline, renamed and given this item's model
component before launch. Baseline non-player display entities were removed with
`/kill @e[type=!minecraft:player]`; `/tp @s 10 -60 3.5 -180 0` supplied the clear
background. Chat faded before capture. The image is the raw Xvfb root screenshot,
without resizing, compositing or painting. Only this model and native block references
are in the minimal resource pack. This verifies singleplayer client rendering, not
an Astera deployment or production pack build.

Reproduce from the parent of this commit, using a fresh project directory:

```sh
git show <parent>:content/models/items/thunder-staff.model.json > build/thunder-staff-before.json
(cd tools/skill-viewer && bun install --frozen-lockfile)
python3 tools/model-forge/batches/vanilla-material/thunder-staff/remake.py \
  --project build/thunder-staff-reproduction --before build/thunder-staff-before.json
python3 tools/model-forge/batches/vanilla-material/thunder-staff/capture-pack.py \
  --out build/thunder-staff-client/resourcepacks/vanilla-material
```

Catalog wish: a quiet brown leather material; brown wool is the closest current entry.
Amethyst facets have intentionally stronger variation than the original painted purple.
Offline lighting and cameras are approximate. Existing tiny ornament gaps/overlaps remain;
this material-only pass is not a geometry cleanup. Production pack compilation and server
integration remain manager verification. No tests, Gradle, Docker, E2E, dedicated server or push ran.
