# Shotgun vanilla materials

The existing pump shotgun silhouette and every display transform are retained.
Three barrel facets slightly longer than 16 units split into two cells each,
124 → 127 elements; internal split faces are omitted.

| Parts | Catalog material |
|---|---|
| Stock, cheek comb, wrist, pump and raised wooden ribs | `wood.stripped_dark_oak_log` |
| Receiver, bevels, barrel, lower tube, guards, rails, ferrules, shell carrier | `metal.netherite_block` |
| Fasteners, screws, trigger, muzzle rim, tube caps, bead, highlight rails | `metal.iron_block` |
| Shell heads | `metal.gold_block` |
| Shell cases, seams, selectors, identity stripes | `concrete.red_concrete` |
| Bore and ejection recesses | `concrete.black_concrete` |
| Buttpad and buttplate ribs | `cloth.black_wool` |

No custom PNG or Mojang texture is installed. All references use the block atlas.
Seed 20261023 crops at one texel per local unit on every face; display scaling
scales those texels with the model. Wood follows each cuboid's longest axis,
including the vertical raised pump ribs; the main pump and stock run lengthwise.

## Visual review

Opened before/after eight-view boards, the Forge contexts board, the player
contexts board, the 26-direction contact sheet, and 16/32/64px GUI samples.
The thin steel highlight rails make the dark barrel readable; red shells and
brass heads remain distinct. The muzzle stays black with a bright metal rim.
The barrel split near the muzzle shows no conspicuous seam at these views.
32/64px inventory retains material separation; at 16px it mainly reads by outline.
[client.png](client.png) and [client-offhand.png](client-offhand.png) are native,
unedited 1280×720 Xvfb captures of isolated 26.3 creative superflat singleplayer.
Both first-person hands leave the screen center clear; both third-person hands
point the barrel forward, including the side view. Ground/frame placement and
original proportions are retained. No concept was supplied; before is regression
reference only. Existing stepped facets, small detached details and overlaps
were preserved rather than redesigned.

Forge validation has zero errors and conservative warnings: 402 overlaps,
9 coplanar contacts, 1 disconnected-component report. These are not a complete
z-fighting audit. Direct checks on the real exported assets confirmed display
identity, unchanged cuboids except the three equal-volume splits, one texel/unit
on every face, deterministic replay and block-only texture references.

## Reproduction

Save the pre-change model from this commit's parent, then:

```sh
python3 tools/model-forge/batches/vanilla-material/shotgun/remake.py \
  --project /tmp/shotgun-material-fresh --before /tmp/shotgun-before.json
python3 tools/model-forge/context_preview.py --out build/shotgun-contexts shotgun
python3 tools/model-forge/context_preview.py --viewer --out build/shotgun-reviewer shotgun
bun tools/model-forge/review.mjs build/shotgun-reviewer/viewer.html build/shotgun-angle-review
flock /tmp/claude-1000/astera-mc-client.lock \
  python3 tools/model-forge/batches/vanilla-material/shotgun/capture-client.py
```

The client helper creates a minimal capture pack with the exact authored model,
uses the existing portablemc 26.3 runtime and a dynamically allocated Xvfb display,
and waits for `build/shotgun-client/stop`. Create an isolated creative superflat
with commands enabled and give `iron_axe[item_model="astera:shotgun"]`.
Touch `build/shotgun-client/capture` then `stop` to capture the unedited Xvfb root
window into client.png and terminate the isolated client. Default FOV 70,
1280×720, GUI scale 2, software GL and view bob disabled.

## Remaining risks / catalog wishes

- Native main/offhand images were opened and reviewed. Dark barrel rails, wooden
  pump and red/gold shells render correctly; screen center remains clear.
  Native lighting is darker than Forge, and the stock continues below the screen
  with the retained transform. No model/texture/atlas errors appeared in the log.
  Unrelated offline-account Realms, unavailable audio and end_of_frame warnings remain.
- Production pack build/deployment and use animations remain manager verification.
- Original overlaps and very small faceted pieces remain; offline warnings are conservative.
- Netherite is the closest catalog metal to blued steel, but has broad panel marks;
  a quieter dark steel material would suit firearms. No shared catalog edits made.
- No Gradle, unit tests, E2E, Docker, servers or push were run.
