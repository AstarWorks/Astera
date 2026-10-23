# Vanilla material pilot — verdict-hammer

The pilot keeps the original outline, bevel cuboids, ornament positions and all display
transforms. The 19-unit haft becomes two external-face-only cells: 161 → 162 elements.
Materials are stripped dark oak, polished blackstone, gold, iron, diamond and brown wool.
Only the two seal inscriptions use a new custom 16×16 rune atlas. No Mojang PNG is shipped.

| Evidence | Observation |
|---|---|
| [before.png](before.png) / [after.png](after.png) | Forge `board`, same eight cameras, 384px tiles and shared framing. Both PNGs opened and reviewed. Head/haft outline retained; stone noise, gold variation and wood grain replace painted fills. |
| [contexts.png](contexts.png) | GUI, both approximate first/third person hands, ground and fixed. Opened and reviewed. No transform edits. |
| [client.png](client.png) | Actual 26.3 client, Xvfb/llvmpipe, 1280×720 first person. Opened and reviewed. Blackstone, gold, wooden haft and cyan inscription render with native texture references. |
| [evidence.json](evidence.json) | Model/texture/evidence hashes and capture context. |
| [operations.json](operations.json) | Original operation sequence, arguments and validation warning counts. |

Material UV density is one vanilla texel per local model unit, including fractions.
Display scaling scales the pixels with the geometry; no compensating increase for the
0.56 hand scale. Custom rune faces intentionally use the whole rune atlas, up to 10
texels/unit. Grain follows the original haft's Y axis; its hidden end caps use log tops.
Crop offsets are deterministic with seed 20261023. All eight canonical views and the
context board show the same part proportions; the two seal faces now show an inscription.

## Runtime finding and production path

26.3 `MaterialBaker.bake` tries item then block atlas. However,
`CuboidItemModelWrapper.validateAtlasUsage` refuses one model using both. The first
capture attempt put the custom rune in `items/`, which reproduced the missing-model
cube and `Multiple atlases used in model`. Duplicating vanilla sources into the item
atlas renders but logs a future-rejection warning. The final asset instead puts its
custom rune at `astera:blocks/verdict-hammer-runes`, sharing the block atlas with native
materials. The final reload has neither mixed-atlas errors nor duplicate-sprite warnings.

`ResourcePackBuilder.addModels` copies JSON bytes verbatim; `addTextures` only reads
`content/textures`, and `addAtlases` already registers the custom `blocks/` directory.
`make dev-pack` refreshes the hash of an already rebuilt pack. `ItemModelCatalog` and
`PaperItemMaterializer` select `astera:verdict-hammer` without rewriting textures.
No production builder/runtime change is needed. A shipped-content integration assertion
in `PackValidationTest` covers verbatim model bytes, native references without copied
PNGs, the custom block source and the item definition's model key.

The capture uses an isolated creative superflat singleplayer and the existing portablemc
26.3 runtime used by vfx-shot. It holds
`minecraft:iron_axe[minecraft:item_model="astera:verdict-hammer"]` with the exact authored
model and the builder-equivalent block atlas source. It does not claim an Astera server
or the production-built ZIP was deployed. No Gradle, tests, Docker or push was run.

## Reproduction

Save the original model from the parent of this change, then use a fresh project:

```sh
git show <parent-commit>:content/models/items/verdict-hammer.model.json > /tmp/hammer-before.json
python3 tools/model-forge/batches/vanilla-material/remake.py \
  --project /tmp/hammer-material-round --before /tmp/hammer-before.json
```

The script uses Forge operations and writes only the authorized hammer, its new rune
PNG and this batch's evidence. Set `MODEL_FORGE_CLIENT_JAR` for another local client jar.
To assemble isolated real-client inputs without Gradle/Docker:

```sh
python3 tools/model-forge/batches/vanilla-material/capture-pack.py \
  --out build/material-client/resourcepacks/vanilla-material
```

Start the already installed 26.3 portablemc runtime with a fresh `--mc-dir` under Xvfb
and software GL (`LIBGL_ALWAYS_SOFTWARE=1`, `LP_NUM_THREADS=4`), at 1280×720. Enable
`file/vanilla-material`, create a creative superflat with commands enabled, then run the
`give` command above. Default FOV 70, view bob off, GUI scale 2. Capture the Xvfb root
window without resizing or painting. `client.png` is that raw screenshot.

Manager/CI verification, **not executed by the worker**:

```sh
python3 tools/model-forge/batches/vanilla-material/integration.py
# Existing resource-pack shipped-content checks via manager CI/deploy.
```

The integration script loads the real shipped model, splits a long haft through a
batch material operation, checks density, deterministic replay, undo/restore, failed-batch
rollback, export/reimport and a useful missing-jar render error. It uses no mocks or fakes.

## Remaining limits

- Forge's orthographic camera, approximate hand and illustrative light differ visibly
  from native first-person perspective. The client head is larger and its gold darker;
  the handle continues below the screen. Native material detail and inscription agree.
- Original intentional bevel overlaps and tiny ornament separations remain. Validation
  has zero errors; conservative warnings change from 591 overlaps / 8 contacts / 1
  disconnected-component report to 593 / 9 / 1 after splitting. The extra contact is
  the haft split; its internal faces are omitted. This is not a complete z-fighting audit.
- Custom atlases must share the native material atlas: `blocks/` with vanilla blocks,
  `items/` with vanilla items. Native block and item textures together in one 26.3 cuboid
  model are unsupported by the client. Forge can inspect these references individually;
  validation warns for recognized atlas paths, but an offline board does not reproduce
  the client's atlas constraint.
- The animated resource denylist is from the provided 26.3 jar. Preview also rejects
  new animation metadata and non-16×16 assets. Offline validation cannot establish that
  an arbitrary raw resource exists in a different client version without rendering it.
- Production pack compilation, integration execution and server-side deployment remain
  manager CI responsibilities under the task's no-local-tests/Gradle/Docker rule.
