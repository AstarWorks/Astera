# Match arsenal: tools, currency and building icons

Generated with `tools/item-art/scripts/gen_sheet.py` from
`tools/item-art/sheets/arsenal-tools-v1.yaml` (Codex image tool; no external asset downloads).
The committed source sheet is the locked silhouette/palette reference; its hash and every
final asset hash are in `evidence.json`. `:tools:item-art:run` supplies the 32px rasters and
fallback models. New authored models override those fallbacks through the existing pack
builder's same-basename selection. No item schema, manifest or item IDs changed.

## Assets

All texture paths below are under `content/textures/generated/items/`.
Tools reference native vanilla materials, not these rasters, when rendered in 3D.
Currency and block sprites use generated parents with authored hand display transforms.

| Item ID | Model under `content/models/` | Texture |
| --- | --- | --- |
| stone-pickaxe | `items/stone-pickaxe.model.json` | `stone-pickaxe.png` |
| stone-axe | `items/stone-axe.model.json` | `stone-axe.png` |
| stone-shovel | `items/stone-shovel.model.json` | `stone-shovel.png` |
| iron-pickaxe | `items/iron-pickaxe.model.json` | `iron-pickaxe.png` |
| iron-axe | `items/iron-axe.model.json` | `iron-axe.png` |
| iron-shovel | `items/iron-shovel.model.json` | `iron-shovel.png` |
| diamond-pickaxe | `items/diamond-pickaxe.model.json` | `diamond-pickaxe.png` |
| diamond-axe | `items/diamond-axe.model.json` | `diamond-axe.png` |
| diamond-shovel | `items/diamond-shovel.model.json` | `diamond-shovel.png` |
| ore-iron | `items/arsenal/ore-iron.model.json` | `ore-iron.png` |
| ore-gold | `items/arsenal/ore-gold.model.json` | `ore-gold.png` |
| ore-diamond | `items/arsenal/ore-diamond.model.json` | `ore-diamond.png` |
| ore-emerald | `items/arsenal/ore-emerald.model.json` | `ore-emerald.png` |
| reinforced-planks-block | `items/arsenal/reinforced-planks-block.model.json` | `reinforced-planks-block.png` |
| stone-bulwark-block | `items/arsenal/stone-bulwark-block.model.json` | `stone-bulwark-block.png` |
| obsidian-plate-block | `items/arsenal/obsidian-plate-block.model.json` | `obsidian-plate-block.png` |
| packed-clay-block | `items/arsenal/packed-clay-block.model.json` | `packed-clay-block.png` |
| tempered-glass-block | `items/arsenal/tempered-glass-block.model.json` | `tempered-glass-block.png` |
| hardened-soil-block | `items/arsenal/hardened-soil-block.model.json` | `hardened-soil-block.png` |
| wool-block | `items/arsenal/wool-block.model.json` | `wool-block.png` |

## Visual rounds and evidence

Evidence root: `/tmp/claude-1000/codex/arsenal-tools-art2/`.
The actual PNG/JPG files were opened during authoring; tool success alone was not approval.

1. Concept: inspected the generated sheet. Stone grey, white-silver iron, cyan diamond;
   shared dark oak/leather/brass fittings. Currency shares a gold seal but has four different
   rock/crystal silhouettes. All seven building materials have individual cube silhouettes.
2. Blockout: Forge history checkpoints, eight-view boards and nine player/GUI/ground/frame
   contexts for every tool. Critic: short pick wings, overly plain shovel and grip.
3. Shape/detail: extended both pick wings, raised shovel spine/shoulder lips, added grip
   bindings, heel and socket fasteners. Verifier: silhouettes and material tiers improved;
   top/side views revealed detached shovel pins because its neck is narrower than the sockets.
4. Pin correction: moved the shovel pins to touch the neck (front z 6.7, back z 9).
   Checked the new eight-view boards. The 26-direction/9-context sweep (315 captures) was
   opened as `angles-stone.png`, `angles-iron.png`, `angles-diamond.png` plus context boards.
5. Surface cleanup: removed only fully covered opaque faces: shaft underside under brass
   heel; shovel shaft top inside neck; neck underside inside ferrule. Same-facing coplanar /
   <=0.021-unit surface candidates fell from 1/1/3 to zero for pick/axe/shovel, all tiers.
   Opened the underside/oblique closeups in `surface-final.png`; visible geometry retained.
6. Display polish: corrected double-mirroring in the left-hand display transforms (Java
   already mirrors Y/Z). Added centered hand transforms for all eleven generated sprites
   after the inherited vanilla pose clipped in the offline preview. Final hand/GUI sheet
   is `all-assets-hand-gui.png`; all nine contexts are under `contexts-final/`.
7. Raster refinement: inspected 16-colour conversion (`icons-round-1.png`), then regenerated
   with 24 colours and inspected `icons-final.png`; crystal facets, ore seal and glass frame
   stay distinct. Native tool GUI at 16/32/64px is `native-gui.png` (16px is a stress check).

Forge project snapshots, transcripts, measurements and validation results are under
`forge/<id>/`. Tool models have 16–19 visible-purpose elements: these are working tools,
not the 80–200-element hero props; no invisible geometry was added to meet a count.
All vanilla faces are cropped through `material.apply` at one texel per local model unit;
wood grain follows the shaft. Native material PNGs remain in the ignored local preview
cache. No Mojang or recoloured Mojang PNG is committed.

Reproduce: run `build.py 1` through `build.py 5`, **inspecting each round**, with
`--out <fresh-evidence-directory>` and `MODEL_FORGE_CLIENT_JAR` pointing to the local jar.
Run `context_preview.py` with the nine explicit tool IDs, then this batch's
`icon_contexts.py <contexts-directory>` to include the eleven sprites in the same shared
context renderer. The latter uses cutout front/back planes; it omits vanilla generated
sprites' tiny side extrusion and does not author alternate geometry.

## Validation and limits

- Passed `:tools:item-art:run`, `:tools:e2e:compileTestKotlin`, `contentLint`, and
  `:tools:resource-pack:buildResourcePack`. Unrelated generator output was restored;
  only this lane's twenty generated model/texture pairs are committed.
- Added `ArsenalArtE2eTest#the pushed pack and granted arsenal items select distinct custom art`:
  download the actual pushed ZIP, check all twenty model definitions, nine volumetric tool
  models, eleven generated sprite parents, unique nonempty 32px textures, then grant every
  item and check its server item-model component. **Not executed**; manager's batched e2e.
- No unit/check tasks, live containers, pushes, private content or existing model files touched.
- Live-client pack load, swing/use poses, FOV, glint, and native sprite edges still need a
  client review. Offline renders do not establish those properties. The sheet supplies a
  single view per item, not aligned orthographic references: no four-view target IoU claimed.

## Placed blocks: mechanism absent

`CustomBlockYamlConfig` has `base_bukkit_material`, with no texture/model/display selector.
`core/feature-gameplay/.../PaperBlockStateGateway.kt` places it with `block.type = material`.
The pack builder ships models/textures but has no custom-block visual allocation or placement
binding. Therefore the six placed blocks retain their vanilla appearances; no placed texture
files or block-state hijack were introduced. A separately owned runtime design needs a custom
block visual identifier, a placement/chunk-reload/removal rendering lifecycle, and pack-side
model/texture registration (with collision/break behavior preserved). A block texture alone
cannot reach this runtime path.

## Documentation/code mismatches found

- `docs/art/3d-model-pipeline.md` prescribes <=64 elements and 2 texels/unit; current
  Forge README/AGENT and `material.apply` use no artificial cap and one vanilla texel/unit.
  Followed current code/material protocol.
- `.claude/rules/ai-delegation.md` and `wave-execution.md` say authoring tools were extracted
  and no longer live here. This tree actually contains `tools/item-art` and `tools/model-forge`;
  followed the explicitly requested current shared pipeline.
- `docs/vision/areas/modes-combat.md` still says no shop route, player-placeable blocks or
  e2e walk, while this base already contains the match ore shop, `placed_from` block mappings,
  and `MatchArsenalE2eTest`. Area-spec edits are owned by ops-harness; request manager update.
