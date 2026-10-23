# Vanilla materials — rifle

All 137 cuboids retain their names, geometry, rotations and display transforms.
No splits are needed. Only material references and face UVs change; there are no
custom PNGs and no copied Mojang textures.

| Parts | Catalog material |
|---|---|
| Stock, stock neck, cheek comb, forearm | `wood.stripped_dark_oak_log` |
| Forearm fluting | `wood.stripped_spruce_log` |
| Receiver, magazine, scope bodies, guard, trigger, rail, muzzle shroud | `metal.netherite_block` |
| Barrel | `metal.anvil` |
| Receiver bevels, screws/fasteners, barrel collars, scope clamps, grip ribs, forearm edges, lens retaining lip | `metal.iron_block` |
| Grip, buttpad, buttplate ribs | `cloth.black_wool` |
| Scope lens and exposed lens center | `gem.emerald_block` |
| Receiver identity stripe | `metal.oxidized_copper` |
| Ejection recess, muzzle face and ports | `concrete.black_concrete` |

`material.apply` uses seed 20261023. All 822 faces retain one vanilla texel per
model unit (measured 0.9999999999999952–1.0000000000000029). Wood grain follows
the longest local axis; end faces use native log tops. No density compensation
for display scaling. Replaying the recipe in a fresh project produced identical
model JSON and before/after/context PNG bytes.

Opened and reviewed: `before.png`, `after.png` (same eight cameras), `contexts.png`
(Forge), `player-contexts.jpg` (perspective player, both hands, ground/spin, frame,
GUI), `angles.jpg` (26 directions with `cameras.json`) and `inventory.png`
(16/32/64px; 16px is a nearest-neighbor downsample from Forge's minimum 32px).
The barrel initially used netherite; its varied crops made the thin tube appear
patchy. Anvil makes that part quieter. The grip initially used wood; the final
black wrap and iron ribs restore the original dark grip. Green emerald lens and
oxidized-copper stripe preserve the original green accent role. The dark stock,
bright collars and long barrel stay distinguishable at 32/64px. At 16px the rifle
outline survives while small ribs and sight detail disappear.

Critic/verifier: the stock, barrel and scope silhouettes are unchanged across
canonical and orbit views; there is no new gap or visible material seam. Both
perspective hand views point forward and leave the center clear. The original
first-person transforms show a large stock near the lower edge; retained as
required. Third person, dropped and frame placement retain the original scale.
No image-generated concept was supplied; the before image is regression evidence.

Validation has zero errors. The original 311 AABB overlap reports, 25 possible
coplanar contacts and one disconnected-component report are unchanged. Existing
bevel/facet overlaps remain; this material pass is not a full z-fighting audit.

`client.png` was opened and reviewed: the native 26.3 first-person view shows
resolved wood grain, iron collars/bevels, a quiet anvil barrel, dark scope/body,
and a mint receiver stripe. The stock fills the lower-right foreground using
the unchanged transforms. No missing-model cube, stretched barrel texture or
new visible seam is present. The green objective lens faces away in this view;
its front is covered by the canonical/orbit boards. `client-log.txt` records no
missing model/texture, mixed-atlas or duplicate-sprite diagnostics. The offline
launcher reports expected authentication, unavailable audio and post-effect
warnings; these do not affect model rendering. The capture used a copy of a
prepared creative superflat save in the dedicated game directory.

## Reproduction

From the repository root, use fresh project/evidence directories:

```sh
git show HEAD^:content/models/items/rifle.model.json > /tmp/rifle-before.json
python3 tools/model-forge/batches/vanilla-material/rifle/remake.py \
  --project build/rifle-reproduction --before /tmp/rifle-before.json
python3 tools/model-forge/context_preview.py --out build/rifle-contexts rifle
python3 tools/model-forge/context_preview.py --viewer --out build/rifle-viewer rifle
bun tools/model-forge/review.mjs build/rifle-viewer/viewer.html build/rifle-angles rifle
```

The viewer embeds cached vanilla pixels and must stay local/ignored. The runtime
capture script builds a minimal pack from the exact authored model and no PNGs.
Provide an isolated creative superflat with commands enabled at
`build/rifle-client/saves/rifle-review`, then run:

```sh
flock /tmp/claude-1000/astera-mc-client.lock \
  python3 tools/model-forge/batches/vanilla-material/rifle/capture-client.py
```

It uses the pilot's installed portablemc 26.3 runtime, a unique Xvfb display via
`-displayfd`, software GL, 1280×720, FOV 70, view bob off and GUI scale 2. The game
directory contains `rifle`. The held item is
`minecraft:iron_hoe[minecraft:item_model="astera:rifle"]`. The screenshot is the
native Xvfb root capture, without resizing or painting.

## Remaining risks / catalog wishes

Quiet blued steel and black leather/rubber would better match the original rifle;
netherite/anvil and black wool are the closest existing catalog choices. No shared
catalog change is needed. Existing intersecting bevels remain. Alternate FOVs,
combat/use animations, shader packs and production pack deployment are outside
this capture. Production integration checks remain manager/CI work.
No Gradle, tests, E2E, Docker, external server or push was run.
