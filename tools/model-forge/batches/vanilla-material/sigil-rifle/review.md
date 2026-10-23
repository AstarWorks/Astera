# Vanilla materials — sigil-rifle

All 174 cuboids retain their names, coordinates, rotations and display transforms.
Only materials and UVs change. No custom texture is necessary: the raised muzzle
sigil already has geometry. No Mojang PNG is installed or committed.

| Parts | Catalog material |
|---|---|
| Stock, heel, neck, cheek comb, grip, forearm | `wood.stripped_dark_oak_log` |
| Receiver, barrel, collars, guard, side plates, rail teeth | `metal.netherite_block` |
| Heraldic trim, bevels, grip ribs, scrolls, bands, muzzle sigil and raised runes | `metal.gold_block` |
| Fasteners, screws, trigger, muzzle shroud | `metal.iron_block` |
| Red binding facets | `cloth.red_wool` |
| Chamber, jewels, selector, fluting, identity stripes, edge and slits | `gem.redstone_block` |
| Buttpad/ribs, muzzle face/ports, ejection recesses | `concrete.black_concrete` |

Forge `material.apply`, seed 20261023, textures all 1,044 faces at one vanilla
texel per model unit (measured 0.9999999999999952–1.000000000000006). No cuboid
needs splitting. Wood grain follows each part's long axis; the stock is horizontal
and the grip vertical. Fractional crops keep thin gold lines quiet. Only used
block slots and a netherite particle remain; the original item-atlas slot is removed.
The intermediate batch warns about the unused old item slot; the installed/reimported
model has no mixed-atlas warning.

Opened and reviewed `before.png`, `after.png`, `contexts.png`, `player-contexts.jpg`,
`angles.jpg` (26 model directions; `cameras.json`), and `inventory.png` (16/32/64px).
A close perspective inspection of e0104 and the muzzle ring confirms the retained
layered sigil construction. No geometry or display adjustment was needed.
The stock becomes warmer wood while red identity details and gold bevels keep the
original visual hierarchy. Front/back, underside and oblique views show the same
outline and no new gaps. Both player hand contexts point forward and keep screen
center clear. Ground and frame placement remain unchanged. At 16px the outline,
gold ring and red bands survive; fine scrolls and raised runes merge into trim.

Validation has zero errors. Original conservative reports remain 378 AABB overlaps,
22 possible coplanar contacts and one disconnected-component report. These include
intentional layered ornament and bevels; preserving them is not a complete z-fighting
audit. No concept image was provided; before/after is silhouette regression evidence.

`client.png` is the unedited 1280×720 native screenshot from portablemc 26.3,
Xvfb/llvmpipe, FOV 70, bob off, GUI scale 2. Opened and reviewed: the gold muzzle
sigil is open and readable; red bands/slits, dark receiver, iron muzzle and wooden
stock resolve correctly. The center remains clear. As in the original transforms,
the large near stock extends below/right of the frame. This is a native first-person
check, not combat/charging animation verification. `client-log.txt` has no missing
model/texture, mixed-atlas or duplicate-sprite warning. The offline launch records
unrelated Realms authentication and `minecraft:end_of_frame` post-effect messages.

## Reproduction

From repository root, save the original from this commit's parent and use a fresh project:

```sh
git show HEAD^:content/models/items/sigil-rifle.model.json > build/sigil-rifle-before.json
python3 tools/model-forge/batches/vanilla-material/sigil-rifle/remake.py \
  --project build/sigil-rifle-reproduction --before build/sigil-rifle-before.json
python3 tools/model-forge/context_preview.py --out build/sigil-rifle-contexts sigil-rifle
python3 tools/model-forge/context_preview.py --viewer --out build/sigil-rifle-viewer sigil-rifle
bun tools/model-forge/review.mjs build/sigil-rifle-viewer/viewer.html build/sigil-rifle-review sigil-rifle
flock /tmp/claude-1000/astera-mc-client.lock \
  python3 tools/model-forge/batches/vanilla-material/sigil-rifle/capture-client.py
```

The capture script assembles a fresh minimal pack, starts portablemc 26.3 on a unique
Xvfb display (`-displayfd`), then waits for manual world setup. This capture uses an isolated copy of the completed
dagger review creative-superflat world, with its inventory cleared before giving the
rifle. In creative superflat
with commands, give `minecraft:crossbow[minecraft:item_model="astera:sigil-rifle"]`.
Create `build/sigil-rifle-client/capture` and `build/sigil-rifle-client/stop` to save
the raw root screenshot and terminate this isolated client. The entire launch and
capture must stay inside the lock. Native GL uses llvmpipe, resolution 1280×720,
FOV 70, view bob off and GUI scale 2. Pack source is the exact authored JSON plus
vanilla block references; no copied vanilla sprites or custom PNGs.

## Remaining limits

- Catalog wishes: none. Existing static entries cover the chosen materials.
- Pre-existing overlapping ornament, bevels and near-coplanar contacts remain.
- Offline hand/camera/light approximations differ from native first-person rendering.
- Combat animations, other FOVs, shaders and production pack deployment are outside
  this isolated capture. Production integration checks remain manager/CI work.
- No Gradle, unit/integration tests, E2E, Docker, external servers or push was run.
