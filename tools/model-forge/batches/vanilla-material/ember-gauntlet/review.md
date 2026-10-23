# Ember gauntlet — vanilla materials

| Parts | Material |
| --- | --- |
| Cuff armor, palm, raised backhand, fingers, thumb, wrist, vent grilles and overlapping lames | `metal.anvil` |
| Original bronze cuff sections, finger lips, border, thumb top, rivets and crest bezel | `metal.copper_block` |
| Original bright cuff sections, finger bevels, shield rims, lame highlights and rear seams | `metal.iron_block` |
| Existing furnace windows, heat slits, knuckle heat and crest core | Custom orange ember pixels |
| Existing knuckle yellow/front cores and vent hot cores | Custom pale yellow ember pixels |

The single custom 16×16 `astera:blocks/ember-gauntlet-embers` atlas contains only
orange/yellow glow colors. It supplies existing glow geometry, without an emissive
extension. All other faces use deterministic `material.apply` crops at one vanilla
texel per local model unit (seed 20261023). No copied Mojang PNGs. All references
share the block atlas. Catalog wish: a static dark blue-gray steel material; anvil
is the closest available dark metal. Copper replaces the original bronze-gold.

## Review and verification

Opened before.png, after.png, contexts.png, player-contexts.jpg, sweep.png and
inventory.png. The eight-view boards use identical 384px cameras. The sweep covers
26 directions; the perspective player board covers GUI, both first/third-person
hands, third-person side, two dropped-item spins and fixed/frame.

- Silhouette/proportions: yes, unchanged against the supplied original model.
  No external concept was supplied; this is regression evidence, not a concept match.
- Geometry/display: all 145 cuboids preserved without splits; names, rotations and
  display transforms match. Coordinate serialization differs by less than 1e-12.
- Bevels/joins: fingers, thumb, copper cuff perimeter, white cuff bands and crest
  remain distinct in front/back/side/oblique views. Original intersections retained.
- UVs: broad armor has visible dark metal variation, with no stretched textures.
  Original cuff elements share one name but three palette regions; their original
  dark, bronze and bright finishes are mapped separately. Final board restores
  the warm cuff edges and bright intermediate band lost in the first draft.
- GUI: overall gauntlet and orange/dark contrast survive small inventory sizes;
  individual rivets and vent cores cannot be resolved at 16px.
- Hands/ground/frame: both fists point forward in the approximate first-person
  board, screen center stays clear, third-person bracers cover the forearms;
  dropped and fixed contexts retain the original centering and scale.

Forge validation: zero errors. Before and after both report 290 conservative AABB
overlaps, 20 possible coplanar contacts and one disconnected-components warning.
These pre-existing candidates are not proof of visible fighting or floating parts.
The batch temporarily retains the original unused item texture; installation removes
unused slots and reimports the block-only asset, eliminating the mixed-atlas warning.

## Critic / verifier round

Critic, first draft: the name-only material assignment made every `octagonal cuff
plate` dark. This removed the original warm perimeter and bright middle band.
One proposed fix: assign copper to e0009–e0016, e0025–e0032 and e0121–e0128;
assign iron to e0017–e0024, keeping e0001–e0008 dark. No numeric geometry edits.
Expected result: two warm perimeter bands plus the bright band visible from front,
back, side, bottom and oblique views.

Verifier, final draft: requested material assignment changed; final after.png and
sweep.png restore the distinct cuff bands. Both hands, ground and GUI remain
readable, with no silhouette/display regression. Final validate has zero errors;
the warning counts and all 145 original cuboid bounds are unchanged.

## Reproduction

Preserve the original model from the parent commit, then run from the repository root:

```sh
git show HEAD^:content/models/items/ember-gauntlet.model.json > /tmp/ember-gauntlet-before.json
python3 tools/model-forge/batches/vanilla-material/ember-gauntlet/remake.py --project /tmp/ember-gauntlet-fresh --before /tmp/ember-gauntlet-before.json
python3 tools/model-forge/context_preview.py --out build/ember-gauntlet-contexts ember-gauntlet
python3 tools/model-forge/context_preview.py --viewer --out build/ember-gauntlet-viewer ember-gauntlet
bun tools/model-forge/review.mjs build/ember-gauntlet-viewer/viewer.html build/ember-gauntlet-review ember-gauntlet
python3 tools/model-forge/batches/vanilla-material/ember-gauntlet/capture-pack.py --out build/ember-gauntlet-client/resourcepacks/vanilla-material
```

Use fresh output directories. The model/texture are installed through the same
Forge packaging/reimport step as the pilot. Operations and validation warning counts
are recorded in operations.json. Native capture uses the installed portablemc:
`~/.cache/astera-vfx-shot/venv/bin/portablemc --main-dir ~/.cache/astera-vfx-shot/mcdir start 26.3 --mc-dir "$PWD/build/ember-gauntlet-client" -u EmberMaterial --resolution 1280x720 --jvm-arg=-Xmx1G`.
Wrap the complete Xvfb launch, client lifetime and root-window capture in
`flock /tmp/claude-1000/astera-mc-client.lock bash ...`. Allocate a unique display
with `Xvfb -displayfd 1 -screen 0 1280x720x24 -nolisten tcp`. Use software GL,
`LP_NUM_THREADS=4`, pack `file/vanilla-material`, default FOV 70, GUI scale 2,
view bob off, and an isolated creative superflat with commands enabled.
Give `minecraft:iron_axe[minecraft:item_model="astera:ember-gauntlet"]`.
Capture with `DISPLAY=:$display import -window root client.png`, without resizing,
painting or compositing.

## Native client verdict

Opened and reviewed client.png: raw 1280×720 root-window screenshot from real 26.3,
portablemc, Xvfb display allocated by `-displayfd`, llvmpipe. A newly created creative,
peaceful superflat with structures disabled uses the exact capture pack, FOV 70,
view bob off and GUI scale 2. Player stands at `0.5 -60 3.5`, yaw 180, pitch 0,
holding the item in the main hand. The full launch/capture/cleanup lifetime held
`/tmp/claude-1000/astera-mc-client.lock`. Game directory: `build/ember-gauntlet-client`.

Dark metal variation, copper edge strips, iron band and orange/yellow furnace accents
are visible in the native first-person view. The fist projects toward the center
without covering the crosshair. Its bracer continues below the screen, consistent
with the retained first-person transform. Native lighting differs from Forge's
illustrative light but preserves the material contrast. No missing-model/texture,
mixed-atlas or duplicate-sprite warnings found in the native client log. The screenshot
was captured after command messages faded, without image editing. Client shut down
and the shared lock released after inspection. No Astera server or production pack
was deployed; this confirms the exact model in a minimal client pack.

## Remaining risks

Original intersections and display transforms are retained. Anvil's neutral charcoal
and copper's salmon color approximate the original cool steel and bronze. Glow
colors do not emit light. Offline lighting/hand guides differ from the real client.
Production resource-pack build and manager CI were not run. No Gradle, tests, E2E,
Docker, standalone server or push was run.
