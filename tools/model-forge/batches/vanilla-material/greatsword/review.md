# Greatsword: free colour on vanilla patterns

The 2026-10-24 founder decision restores the original blue-steel blade and crimson
fittings using constant face tints on light vanilla iron. No Mojang texture PNG,
recoloured sprite or embedded-pixel HTML viewer is committed or shipped.

| Original parts | Vanilla pattern | RGB tint |
|---|---|---|
| Blade and stepped point | `metal.iron_block` | `#557caa` |
| Bevels, borders, collars, rivets and wear marks | `metal.iron_block` | `#b4cee8` |
| Deep central channel | `metal.iron_block` | `#243c59` |
| Central guard frame and grip collars | `metal.iron_block` | `#354c69` |
| Guard, crimson terminals, pommel and red ornament | `metal.iron_block` | `#bf293e` |
| Dark grip / wraps | stripped dark oak / black wool | Untinted |

`content/models/items/greatsword.model.json` stores five integer RGB entries in
`astera_tints`; 561 faces reference them through `tintindex`. The actual pack
builder removes that field from the cuboid model and emits five
`minecraft:constant` sources in `assets/astera/items/greatsword.json`.
`ItemModelCatalog` discovers the generated item ID, and `PaperItemMaterializer`
selects `astera:greatsword`; the builder resolves its authored model override and
palette together. Neither runtime class needs to know colours or texture paths.

## Mechanism verification

Inspected the installed **26.3 client jar** using `javap -c -p`:

- `ItemModels.bootstrap` registers `minecraft:model` with
  `CuboidItemModelWrapper$Unbaked.MAP_CODEC`.
- That codec reads `ItemTintSources.CODEC.listOf()` as optional `tints`; no fixed
  index-count limit is present. Faces and baked materials store integer indices.
- `ItemTintSources.bootstrap` registers `minecraft:constant`.
  `Constant.MAP_CODEC` reads `value` through `ExtraCodecs.RGB_COLOR_CODEC`; its
  constructor forces opaque alpha.
- `CuboidItemModelWrapper.update` evaluates every source into tintLayers.
  `ItemFeatureRenderer.getLayerColorSafe` selects by the face's index, or white
  for an absent/out-of-range index. Forge and the builder reject invalid indices.
- `CuboidFace$Deserializer.getTintIndex` reads integer `tintindex`, default -1.
  `assets/minecraft/shaders/core/item.fsh` multiplies the sampled texture by
  vertexColor and ColorModulator.

See [ADR-0251](../../../../../docs/adr/0251-custom-colour-tints-on-vanilla-patterns.md).

## Offline review and procedural verification

Opened `before.png`, `after.png`, `contexts.png`, `inventory.png`,
`player-contexts.jpg` and `sweep.jpg`. Before is the original model from
`36743b70`, using its original custom atlas for comparison. After uses native
vanilla resources and face tints. The steel blade is blue again, its central
channel stays darker, bright edges remain readable, and the crossguard/pommel
are red. Dark wraps separate from the brown-black grip. The 16/32/64-pixel GUI
views retain the blue/red identity. Both hands, third-person side, dropped-item
spin, frame and the 26-direction sweep show consistent colours.

`remake.py` runs real Forge import, snapshot, material batches, validation,
rendering and export, without a test runner. It verifies original cuboid
volume/envelope/name/rotation preservation through splits, unchanged display
transforms, deduplicated palette, identical export/import, and zero exported
texture PNGs. Results are in `verification.json`; operations are in
`operations.json`. Original: 96 elements; final: 109 (13 split originals).
Material density stays one texel per local unit. Original bevel intersections
and ornament separations remain; geometry cleanup is outside this colour task.

Reproduce from the repository root with a fresh ignored project directory:

```sh
git show 36743b70:content/models/items/greatsword.model.json > build/greatsword-original.json
python3 tools/model-forge/batches/vanilla-material/greatsword/remake.py \
  --project build/greatsword-tint-replay-new --before build/greatsword-original.json
python3 tools/model-forge/context_preview.py --out build/greatsword-contexts greatsword
python3 tools/model-forge/context_preview.py --viewer --out build/greatsword-viewer greatsword
bun tools/model-forge/review.mjs build/greatsword-viewer/viewer.html build/greatsword-sweep greatsword
```

The local HTML/data contains extracted vanilla pixels; keep it in ignored build
output. Only the captured review images belong in the public repository.

## Native client capture

`capture-client.py` consumes the real builder's `build/resource-pack/astera.zip`.
Its minimal capture pack takes the exact emitted greatsword model, item definition
and pack metadata from that ZIP; all textures resolve from the native block atlas.
The entire isolated singleplayer launch/capture/shutdown holds the shared lock:

```sh
flock /tmp/claude-1000/astera-mc-client.lock \
  python3 tools/model-forge/batches/vanilla-material/greatsword/capture-client.py \
  --world-source '<isolated creative superflat world directory>'
```

The script copies that world into `build/greatsword-client`, enables commands in
its copy, starts portablemc 26.3 under a dynamically allocated Xvfb display and
llvmpipe, and equips
`minecraft:iron_sword[minecraft:item_model="astera:greatsword"]`. FOV 70, view bob
off, GUI scale 2, noon, clear weather, peaceful, position 0/-60/3 and yaw/pitch 0/0.
`client.png` is the native 1280×720 root-window screenshot, without resizing or
painting. No standalone server, Docker, live containers, tests or e2e are used.

Opened `client.png`: the native first-person blade is blue steel with a dark
channel, the guard is crimson, and the hotbar icon retains the same palette.
The model and definition used by the client match the final source/pack exactly.
No model, missing-texture, mixed-atlas or tint-loading errors appear in the client
log. Offline authentication and unavailable audio-device messages are unrelated
to the model capture.

## Limits

Multiplication only darkens and preserves the vanilla pattern's contrast; it
cannot brighten black or make an emissive surface. Dark-base validation is a
resource-name heuristic, independent of the client jar. Tint sources are constant
opaque RGB; biome tint resources and unverified animations stay excluded. The
native capture covers first person and hotbar. Swing/use, glint, shaders and
other native display contexts remain unverified; offline lighting is illustrative.
