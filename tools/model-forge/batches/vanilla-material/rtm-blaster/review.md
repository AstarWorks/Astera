# rtm-blaster — restored palette on vanilla patterns

The colour reference is `36743b70:content/models/items/rtm-blaster.model.json`.
`before.png` renders that reference with its original atlas. Authoring starts from
this change's parent model, preserving all 177 cuboids and the current display
block exactly. Only textures, UV crops, face tint indices and palette metadata change.

| Parts | Vanilla pattern and restored colour |
|---|---|
| Receiver, housing, scope, collars, nozzle | Iron block, dark blue steel |
| Barrel, bevels, clamps, rails, screws, grip ribs | Iron block, original blue silver shades |
| Energy coils | Iron block, purple metal |
| Core, battery, indicators, identity stripes, fluting, bore and lenses | White concrete, original violet/pink/pale pink |
| Stock core, sloping heel, neck, cheek comb | Stripped oak, warm brown with lighter comb |
| Grip, buttpad, buttplate ribs | White wool, blue charcoal |

`palette.json` records every part's sampled original RGB, material and tint.
The historical atlas is byte-identical to the still-shipped original atlas.
The first face's original UV crop supplies each part's average hue; small
channel gains compensate for multiplication against the vanilla base. Wood uses
separate channel gains to preserve brown rather than multiplying two brown layers.
Faces retain one vanilla texel per local model unit, seed 20261024. No splits,
custom sprites, copied vanilla pixels or recoloured texture PNGs are shipped.

## Critic and verifier

Opened the original and final eight-view boards, both-hand player contexts,
26-angle sweep and the 16/32/64px GUI sheet. The first tint round made the oak
stock too dark/orange (e0002, e0006–e0007, e0009). The sole correction raises
wood's RGB gains from 1.2 each to 1.45/1.7/2.7; geometry and other materials stay
fixed. Final verification compares the same cameras.

- Silhouette/ratios: yes. All 177 geometry records and the parent's display compare
  equal; `verification.json` also records a lossless Forge export/import round trip.
- Palette/material: yes. Navy metal, silver edges, brown wood and pink core read as
  separate parts. Purple coils remain metallic; smooth pink inserts avoid noisy
  amethyst patterns. This restores the blaster's long pink chamber identity.
- Coverage/grain: yes. No stretched atlas rectangles or split seams; longitudinal
  wood grain stays visible. The final wood correction improves the match to the
  reference without changing the metal/core contrast in other views.
- Contexts/GUI: yes. Both hands, third-person side, ground and frame keep their
  placement. The 16px image merges fine scope/coil detail, while its brown stock,
  pale edging and pink chamber stay identifiable. 32/64px retain coil separation.
- Joins: unchanged. Existing layered facets remain, including e0017–e0032 and
  e0114–e0133. The sweep shows no newly floating pieces or new surface problems;
  this colour pass does not claim to resolve pre-existing intersections.

`contexts.png` is the nine-view player renderer, with Java left-hand signs and
rest-pose mannequin. `angles.png` and `cameras.json` come from the final portable
reviewer; the pixel-embedded viewer remains in ignored build output.

## Native capture

Opened the final unedited 1280×720 `client.png` from 26.3: navy receiver and
scope, silver rails, warm brown wood, violet coils and the long pink chamber
all render with their tints. The centre aim remains clear. The stock reaches the
lower-right screen edge at the preserved HEAD transform. The final log has no
missing model/texture, tint or mixed-atlas error. This capture used a verified
superflat save copied into `build/rtm-blaster-client-final`; the initial launch
was interrupted before capture, and the final isolated launch completed normally.

`capture-pack.py` moves `astera_tints` into the item
model definition's `minecraft:constant` tint list and strips authoring metadata
from the cuboid JSON, matching the production builder. It copies no vanilla PNGs.
The minimal pack uses the exact authored geometry, native block references and
constant tint values. This is an isolated singleplayer capture, not a production
ZIP deployment.

## Reproduce

From repository root, save the colour target and the parent geometry separately:

```sh
git show 36743b70:content/models/items/rtm-blaster.model.json > build/rtm-blaster-original.json
git show <this-commit-parent>:content/models/items/rtm-blaster.model.json > build/rtm-blaster-head.json
python3 tools/model-forge/batches/vanilla-material/rtm-blaster/remake.py \
  --project build/rtm-blaster-fresh --before build/rtm-blaster-original.json \
  --current build/rtm-blaster-head.json
python3 tools/model-forge/context_preview.py --out build/rtm-blaster-contexts rtm-blaster
flock /tmp/claude-1000/astera-mc-client.lock \
  python3 tools/model-forge/batches/vanilla-material/rtm-blaster/capture-client.py \
  --world-source '<isolated creative superflat world directory>'
```

Use fresh project/game/pack directories (`--game-dir build/rtm-blaster-client-fresh`
selects a fresh capture directory). The capture copies the supplied world
without its session lock, enables commands only in that copy, joins it with
portablemc 26.3 and screenshots the Xvfb root at native 1280×720. `-displayfd`
allocates a unique display; the game directory contains `rtm-blaster`.

## Remaining risks

- Constant tints provide colour, not emission or bloom.
- Native animation/FOV variants and original layered facet overlaps remain.
- Production pack build/deployment and integration checks belong to manager CI.
  No Gradle, test tasks, E2E, Docker, external server or push was run.
- Catalog wish: a light `wood.stripped_birch_log` base; stripped oak with separate
  RGB compensation covers the original warm stock for this pass.
