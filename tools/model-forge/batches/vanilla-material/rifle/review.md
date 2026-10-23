# Rifle palette restoration (ADR-0251)

Restored the original `36743b70` rifle's blue-black steel, silver-blue barrel and
fittings, warm brown stock/forearm and lime lens/identity stripe. `before.png`
is the Forge render of that revision's original atlas. Current HEAD geometry and
display are imported separately: all 137 cuboids, element order, names, bounds,
rotations and display transforms remain exactly unchanged. Only materials, UVs
and constant face tints change. No texture PNGs are installed or exported.

| Original atlas region / parts | Vanilla pattern | Tint |
|---|---|---|
| Blue-black receiver, magazine, scope bodies, rail, guard and collars | `metal.iron_block` | `#293442` |
| Silver-blue barrel, bevels, muzzle shroud, plates, fasteners, scope clamps/turret, grip/magazine ribs and lens lip | `metal.iron_block` | `#8ba4ba` |
| Walnut stock, heel, neck, cheek comb, forearm, fluting and forearm edges | `wood.stripped_oak_log` | `#b98276` |
| Dark grip, buttpad and buttplate ribs | `cloth.white_wool` | `#293442` |
| Ejection recess, muzzle face and ports | `concrete.white_concrete` | `#18212c` |
| Lime lens, exposed lens center, selector and receiver identity stripe | `concrete.white_concrete` | `#83d852` |

The recipe reads each original element's atlas UV quadrant, rather than treating
all similarly named gun parts alike. This also restores the originally dark
barrel collars and wooden forearm edges. Bright iron's multiplication yields
silver-blue steel; light stripped oak retains longitudinal grain while its tint
makes it walnut brown. Quiet white concrete keeps tiny lime details readable.
Five deduplicated RGB entries serve all 822 faces. Seed: 20261024.

## Visual review

Opened `before.png`, `after.png`, `contexts.png`, `inventory.png`,
`player-contexts.jpg` and `angles.jpg` (26 orbit directions, saved cameras).
The original warm wood / cool metal contrast is restored. Bright barrel and
receiver fittings separate from the blue-black body. Lime accents stay green
instead of the previous oxidized-copper mint. Wood grain follows long axes;
no new seams, stretched texels or missing textures are visible. At 32/64px the
stock, silver barrel and green stripe remain readable. At 16px the wood/steel
silhouette survives, while tiny green details and ribs are below pixel scale.
Both hands point forward with the existing screen-center clearance; third
person, ground/spin and frame retain current placement. Existing cuboid
intersections were not changed. No separate concept image was supplied.

## Verification and reproduction

`verification.json` records exact geometry/display preservation, zero validation
errors, identical export/import, zero exported PNGs and one texel per model unit.
Original warnings remain: 311 AABB overlaps, 25 possible coplanar contacts and one
disconnected-component warning. These are conservative pre-existing candidates.
`operations.json` contains Forge imports, scene inspection, snapshots, material
batch, before/after boards, measurements, validation, history diff and export.

```sh
git show 36743b70:content/models/items/rifle.model.json > build/rifle-original.json
# Save the current geometry/display input before replay (recipe must not read its own output).
cp content/models/items/rifle.model.json build/rifle-head.json
python3 tools/model-forge/batches/vanilla-material/rifle/remake.py \
  --project build/rifle-tint-replay-new --before build/rifle-original.json \
  --current build/rifle-head.json
python3 tools/model-forge/context_preview.py --out build/rifle-contexts rifle
python3 tools/model-forge/context_preview.py --viewer --out build/rifle-viewer rifle
bun tools/model-forge/review.mjs build/rifle-viewer/viewer.html build/rifle-angles rifle
./gradlew :tools:resource-pack:buildResourcePack --console=plain
```

The actual resource-pack builder completed successfully. Its emitted rifle model
matches the authored model after removing `astera_tints`; its item definition
contains all five matching `minecraft:constant` tint sources. The capture script
takes the exact model, item definition and pack metadata from that generated ZIP.
Local viewers/cache contain vanilla pixels and remain ignored.

## Native capture

```sh
flock /tmp/claude-1000/astera-mc-client.lock \
  python3 tools/model-forge/batches/vanilla-material/rifle/capture-client.py \
  --world-source '<isolated creative superflat world directory>'
```

The script copies the isolated world into `build/rifle-client`, enables commands
only in the copy, allocates Xvfb with `-displayfd`, and launches portablemc 26.3
with llvmpipe at 1280x720. FOV 70, view bob off, GUI scale 2, noon, clear weather,
peaceful, position 0/-60/0 and yaw/pitch -35/0. The equipped item is
`minecraft:iron_hoe[minecraft:item_model="astera:rifle"]`. `client.png` is the native
root-window screenshot, without resizing or painting.

Opened `client.png`: warm walnut stock/forearm, blue-black receiver/scope,
silver-blue barrel and fittings, lime receiver stripe and a matching hotbar icon
are visibly rendered by the native client. No missing-texture cube, stretched
barrel texture or new seam is visible. The objective lens faces away in this
capture; its green front is covered by the offline front/orbit boards.
`client-log.txt` records selected actual diagnostics. No model/texture, mixed-atlas
or tint-loading errors appear. Offline authentication, unavailable audio and the
post-effect warning are unrelated to this model.

## Remaining risks / catalog wishes

Catalog wish: light stripped birch for more neutral tintable wood; the available
stripped oak pattern works for this walnut palette. No shared catalog edits.
Constant tints do not emit light; the lens is opaque green. Existing intersecting
bevels remain. Other native contexts, alternate FOVs, combat/use animations,
glint/shader packs and deployment remain unverified. Offline lighting is
illustrative. No unit tests, check/test tasks, E2E, Docker, external server, live
containers or push were run.
