# Gossamer katana — vanilla materials

| Parts | Catalog material |
|---|---|
| Curved silver sections, cutting edge, guard web spokes | `metal.iron_block` |
| Dark blade spine, tsuba core | `metal.anvil` |
| Guard rim, ferrules, pommel, pins, habaki engraving | `metal.gold_block` |
| Blue underwrap | `cloth.blue_wool` |
| Crossed crimson silk and side returns | `cloth.red_wool` |

All 148 original cuboids, rotations, face coverage and display transforms are
unchanged. Every face uses one vanilla texel per local model unit, including
fractional dimensions. No split was required. No custom or Mojang PNG is shipped.

The first material pass produced conspicuous alternating blade bands where
independent section crops hit iron borders. The second Forge batch aligns silver,
edge and spine crops within quiet parts of their metal textures, retaining density.
Opened and reviewed `before.png`, final `after.png`, `contexts.png`, `inventory.png`,
`player-contexts.png` and `sweep.png`. Inventory evidence includes 16/32/64px
nearest-neighbor samples; silhouette and metal/gold/grip separation survive. Red/blue grip detail
is clearer at 32/64px; subpixel web spokes and pins merge at 16px as expected. The blade is readable from both sides and
oblique views; red crossings remain distinct against blue, and gold frames the
handle. Thin dark spine visibility in edge views is expected. Original stepped
blade joins remain visible; changing their geometry is outside this task.

The player board includes both hands in first/third person, a third-person side
view, two ground spins, fixed and GUI. The viewer sweep covers 26 directions and
nine contexts with no browser errors; `cameras.json` records its asset and cameras.
The interactive viewer and cached vanilla pixels stay in ignored `build/`/`.cache/`.

## Critic / verifier

- Silhouette faithful to original: yes, eight matching views and identical cuboid
  geometry. No separate concept image was supplied for this material-only task.
- Lengths, proportions, rotations and display fit retained: yes, exact structural
  comparison of all 148 elements and every display transform.
- Front/back/oblique ornament reads: yes; guard and crossed wrap remain visible.
- Joins intentional: existing overlaps retained; no new geometry or face removals.
- UV coverage/palette: yes, block-only references and one texel/unit on every face.
- Proposed fix: e0003–e0050 (silver/edge/spine triplets), replace random UV offsets
  with silver (4,7), edge (4,1), spine (6,5), keeping each rectangle's dimensions.
  Expected: remove dark/light bands caused by texture borders on adjacent pieces.
- Verifier: final eight-view board removes the bands and retains the narrow dark
  spine; neither opposite side nor player contexts regresses. Fine segment joins
  are existing geometry. Native-client verification is recorded below.

## Reproduction

Save the original model from this commit's parent and use a fresh project:

```sh
git show <parent>:content/models/items/gossamer-katana.model.json > /tmp/gossamer-before.json
python3 tools/model-forge/batches/vanilla-material/gossamer-katana/remake.py \
  --project /tmp/gossamer-forge --before /tmp/gossamer-before.json
python3 tools/model-forge/batches/vanilla-material/gossamer-katana/capture-pack.py \
  --out build/gossamer-katana/game/resourcepacks/vanilla-material
```

Forge operations and their validation summaries are in `operations.json`.
Validation has zero errors; conservative warnings are unchanged: 342 AABB overlaps,
19 possible coplanar contacts and one disconnected-component report. These reflect
existing layered wrap, ornaments and stepped blade construction. The transient
mixed-atlas warning in the two material batches refers to the unused original item
texture slot; packaging removes it, and final import has no atlas warning.

## Native client verification

`client.png` is the unedited 1280×720 Xvfb root screenshot from the real Minecraft
Java 26.3 portablemc runtime, llvmpipe/OpenGL. It was opened and reviewed. Capture
ran inside the shared `/tmp/claude-1000/astera-mc-client.lock` for the complete
Xvfb/client session, using a dynamically allocated display and unique game directory
`build/gossamer-katana/game`. No server or production deployment was used.

The isolated singleplayer world is creative superflat, structures off, commands on,
peaceful, noon, FOV 70, view bob off, GUI scale 2. The player holds
`minecraft:iron_sword[minecraft:item_model="astera:gossamer-katana"]`. The minimal
capture pack contains the exact authored model bytes and a builder-equivalent
`blocks/` atlas directory source, with no vanilla PNGs or atlas duplication.

Native iron, dark spine, gold and blue/red cloth are present without missing-model
fallbacks; the screen center is clear. Existing stepped joins are more visible at
native perspective scale, and the grip continues below the frame with the original
display transform. The successful client log has no mixed-atlas, missing-texture,
model-loading or duplicate-sprite warnings. Offline-account Realms authentication
errors are unrelated to this local singleplayer capture.

Launch with the existing portablemc runtime under `flock`, Xvfb `-displayfd`,
`LIBGL_ALWAYS_SOFTWARE=1`, `LP_NUM_THREADS=4`, `SDL_VIDEODRIVER=x11`, at 1280×720.
Truncate the display file before launch and wait for both its number and a successful
`xdpyinfo` response before starting the client. Two startup-only attempts were
corrected (an unsupported launcher option, then a stale-display startup race);
neither involved changing the asset or capture pack. Final capture uses the normal
`start 26.3 --mc-dir ... -u KatanaReview --resolution 1280x720 --jvm-arg=-Xmx1G`.

## Remaining risks and catalog wishes

- Existing tiny intersections and stepped blade joins are retained; this is not a
  full geometry/z-fighting audit.
- Offline lighting and approximate hands differ from the native client; animation,
  swing, FOV variation and shader packs are not covered by still captures.
- Production pack/deployment verification remains with manager CI. No Gradle,
  tests, E2E, Docker, server or push is run by this worker.
- Catalog wish: a low-contrast brushed steel and smooth crimson silk material.
  Iron and red wool are the closest existing entries; the catalog is unchanged.
