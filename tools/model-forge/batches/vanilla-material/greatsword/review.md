# Greatsword vanilla materials

| Parts (original element IDs) | Catalog material |
|---|---|
| Dark steel blade, stepped point, deep central channel, guard central frames, grip collars | `metal.anvil` |
| Steel/point bevels, fuller borders, shoulder collars, iron rivets, pommel studs, forged wear cuts | `metal.iron_block` |
| Red guard, crimson terminals, raised bosses, narrow ridges, pommel bevels/insets | `metal.copper_block` |
| Black grip e0001 | `wood.stripped_dark_oak_log` |
| Black grip wraps e0057–e0066 | `cloth.black_wool` |

No custom texture is needed. Every final reference is a vanilla block resource;
no Mojang PNG is installed in content or the capture pack. The original crimson
metal becomes reddish copper: a dark red metallic catalog entry would preserve
its previous hue more closely. No shared catalog changes were made.

## Native-client finding and fix

The first real 26.3 capture revealed transparent holes in the anvil blade that
Forge's opaque preview did not expose. `anvil_top` has alpha gutters at U=0..3
and U=13..16. The reproducible second batch moves every anvil crop into the
opaque U=3..13 strip without changing its size or texel density. Opened the
reloaded native client and final client.png: the blade is now fully opaque and
its dark center, iron edges, copper guard and wooden/wrapped grip are legible.
All final preview captures were regenerated after this UV correction.

`client.png` is an unedited 1280×720 Xvfb root screenshot from portablemc's 26.3
runtime with llvmpipe. The entire launch/capture/shutdown held
`/tmp/claude-1000/astera-mc-client.lock`; Xvfb selected a unique display with
`-displayfd`. Game directory: `build/greatsword-client`. A fresh creative
superflat singleplayer with commands enabled held
`minecraft:iron_sword[minecraft:item_model="astera:greatsword"]`. FOV 70,
view bob off, GUI scale 2; time noon, clear weather, peaceful, yaw/pitch 0/0.
The minimal capture pack contains the exact authored model and item definition;
all texture references resolve from the vanilla block atlas. There are no custom
sprites, copied vanilla sprites or mixed-atlas errors. This is isolated client
verification, not production-pack deployment or an Astera server capture.

## Review

Opened before.png, after.png and contexts.png: identical eight-view framing,
outline, rotations and display transforms. Dark central metal, bright edges and
warm crossguard read clearly; the black wrap separates from the wooden grip.
Thin iron pieces have a restrained gray/white variation. The 13 Y-axis splits
have no exposed internal caps. Existing bevel intersections, the small shoulder
separations, and the decorative diagonal wear cuts remain in their original places.
No geometry cleanup was authorized. There is no supplied external concept image;
comparison here establishes original-geometry preservation, not concept fidelity.

Opened player-contexts.jpg: GUI, both first-person hands, both third-person hands,
side, two ground spins and fixed. The grip stays in the hand, blade points forward
and upward, and the first-person center remains clear. Ground and frame sizes are
unchanged. Opened sweep.jpg for the 24 oblique/horizontal views and both pole captures; no missing surfaces
or new visible gaps. Small bright wear marks are original raised geometry.

verification.json records unchanged geometry envelopes, volumes and rotations
for all 96 original cuboids, unchanged display data, 109 final cells and 1 texel
per local model unit (floating-point tolerance). Final Forge validation: zero
errors; 187 overlap, 35 contact and one disconnected-component warning, versus
167 / 22 / one before. Additional warnings are split-cell boundary candidates;
these conservative counts do not establish visible fighting. The temporary batch
warning about mixed atlases disappears after unused original item slots are pruned.

Opened inventory.png at 16/32/64 pixels (nearest-neighbor enlargement): the
blade edge and warm crossguard remain distinct; tiny rivets disappear at 16px.

## Reproduction

From the repository root, save the original model from this commit's parent:

```sh
git show <parent>:content/models/items/greatsword.model.json > /tmp/greatsword-original.json
python3 tools/model-forge/batches/vanilla-material/greatsword/remake.py \
  --project /tmp/greatsword-fresh --before /tmp/greatsword-original.json
python3 tools/model-forge/context_preview.py --out build/greatsword-contexts greatsword
python3 tools/model-forge/context_preview.py --viewer --out build/greatsword-reviewer greatsword
bun tools/model-forge/review.mjs build/greatsword-reviewer/viewer.html build/greatsword-sweep greatsword
```

Use a fresh project. Seed 20261023 is deterministic. `operations.json` records the
Forge import, observation, snapshot, batch, packaging re-import, render and export.
The local project, client cache and viewer with embedded vanilla pixels stay ignored.

For native reproduction from the repository root (use a fresh
`build/greatsword-client` directory):

```sh
flock /tmp/claude-1000/astera-mc-client.lock \
  python3 tools/model-forge/batches/vanilla-material/greatsword/capture-client.py
```

The helper uses the already installed portablemc cache, creates the minimal pack,
launches a unique Xvfb, and holds the lock until capture/shutdown. Use xdotool on
`DISPLAY=:$(cat build/greatsword-client/display)` to create a creative superflat
with commands, equip the item above and set the documented camera/weather/time.
Once chat has faded, `touch build/greatsword-client/capture
build/greatsword-client/stop` triggers raw root capture and terminates only this
helper's process group and X server.

## Remaining risks

- Production pack build and integration verification remain manager responsibilities.
  No Gradle, tests, E2E, Docker, server or push was run.
- Existing intentional overlaps and tiny ornament separations remain; no exhaustive
  animation, shader, glint, dynamic FOV or z-fighting audit is claimed.
- Copper is warmer/lighter than the original crimson. Catalog wish: opaque dark red
  metal and a full-width opaque dark steel texture (anvil requires gutter-aware UVs).
- Native evidence covers first person; both hands, third person, ground and fixed
  are additionally covered by the opened offline player/context boards.
