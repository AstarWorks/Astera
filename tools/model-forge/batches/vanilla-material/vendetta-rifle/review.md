# Vendetta rifle palette restoration — ADR-0251

Restored the part palette from `36743b70`, using that revision's model and atlas
for `before.png`. Geometry, rotations, face coverage and the current HEAD display
block remain exactly unchanged (178 elements, no splits). The original atlas
region of each corresponding element determines its colour, avoiding the first
pass's name-based mistakes: the optic is orange, the blade/fullers and fasteners
are blue-gray, and the selector/forearm edges are crimson. Full assignments are
in `verification.json`.

| Original region / parts | Vanilla pattern | Tint |
|---|---|---|
| Dark receiver, barrel, scope, mounts, magazine, guard | iron block | `#283340` |
| Blade, bevels, screws, side plates, scope clamps, grip ribs | iron block (white wool for grip ribs) | `#849bb3` |
| Stock, neck, comb, wood forearm | stripped oak log, grain along part length | `#ad7952` |
| Crimson rings, collars, guard, selectors, forearm edges | iron block | `#df2b55` |
| Butt bindings | white wool | `#df2b55` |
| Magenta insets and jewel facets | iron block | `#ce3ef8` |
| Orange lens and exposed lens center | calcite | `#ff8117` |
| Dark grip/buttpad | white wool | `#283340` |
| Dark muzzle/ports/ejection recess | white concrete | `#283340` |

The original atlas's dominant RGBs are dark steel `(32,40,51)`, blue-gray
`(113,133,153)`, wood `(103,62,36)`, crimson `(190,36,72)`, magenta `(175,53,211)`
and orange `(238,108,19)`. Tints compensate for the light bases' multiplication;
this is hue restoration with vanilla surface variation, not pixel-for-pixel atlas
recreation. Oak is the lightest catalog log; birch would permit more exact colour
control. Calcite supplies an unsaturated faceted optic pattern without fighting
orange, unlike the first pass's cyan diamond block. No custom textures were added.

Opened the before/after eight-view boards, Forge contexts, all nine player
contexts, the 26-direction sweep including poles, and the 16/32/64px inventory
board. The stock is warm brown, the metal cool blue-gray, the optic amber and the
thin accents red/purple. These differences remain visible at GUI size; small
jewels merge at 16px. No new seams, grain-axis errors or gaps were visible.
The original long first-person stock reaches the lower edge; the stored display
is deliberately retained. No locked concept was supplied; before is original
palette evidence. No geometry fix was proposed.

Verifier: exact non-face element and face-coverage equality against HEAD passes;
display equality passes; export/re-import is identical and exports zero PNGs.
Forge validation has zero errors and retains 466 conservative AABB overlaps,
39 possible coplanar contacts, and one disconnected-component report. These are
existing construction warnings, not a complete z-fighting audit. `operations.json`
records Forge edits and `verification.json` records every part/material/tint.

Reproduce from the recolour commit's parent, with a fresh project directory:

```sh
git show 36743b70:content/models/items/vendetta-rifle.model.json > build/vendetta-original.json
git show HEAD^:content/models/items/vendetta-rifle.model.json > build/vendetta-head.json
python3 tools/model-forge/batches/vanilla-material/vendetta-rifle/remake.py \
  --project build/vendetta-replay --before build/vendetta-original.json --current build/vendetta-head.json
python3 tools/model-forge/context_preview.py --out build/vendetta-contexts vendetta-rifle
python3 tools/model-forge/context_preview.py --viewer --out build/vendetta-viewer vendetta-rifle
bun tools/model-forge/review.mjs build/vendetta-viewer/viewer.html build/vendetta-sweep vendetta-rifle
python3 tools/model-forge/batches/vanilla-material/vendetta-rifle/capture-pack.py \
  --out build/vendetta-rifle-client/resourcepacks/vanilla-material
flock /tmp/claude-1000/astera-mc-client.lock \
  python3 tools/model-forge/batches/vanilla-material/vendetta-rifle/capture-client.py \
  --world-source '<isolated superflat world directory>'
```

The minimal capture pack removes `astera_tints` from the cuboid model and emits
six `minecraft:constant` item tint sources, exactly as the pack builder does.
Vanilla textures stay resource references. The native 26.3 portablemc capture uses
an isolated superflat world copy, unique Xvfb display, llvmpipe, 1280×720, FOV 70,
GUI scale 2 and no view bob. Launch, capture and teardown hold the shared lock.

Catalog wish: stripped birch log, for neutral light wood grain with arbitrary tint.
Remaining risks: retained geometry contacts; merged ornaments at tiny inventory
sizes; no swing/use, shaders, glint or production pack/deployment verification.
No Gradle, unit tests, E2E, Docker, external server or push was run.

Native verdict: opened the final unedited `client.png` (1280×720). Cool blue-gray
side plates and bayonet, crimson frames/rings, purple insets and brown stock render
with the authored tints; hotbar colour remains readable and the center is clear.
The retained stock extends through the lower-right edge, matching offline contexts.
The orange optic is verified in the opposite-facing offline views; this native
first-person angle does not expose its front. The copied world is visibly superflat.
Capture commands succeeded. No missing-model, missing-texture, multiple-atlas or
duplicate-sprite messages occur. Offline account/Realms authentication, unavailable
audio and `minecraft:end_of_frame` post-effect messages are unrelated capture-host
limitations. Source cuboid data (minus authoring metadata) and all six native tint
sources exactly match the capture pack. No further material fix was needed.
