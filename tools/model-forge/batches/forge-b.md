# Forge B — seven detailed held props

Branch `wp/forge-b`; worktree `/IdeaProjects/Astera/.claude/worktrees/forge-b`.
Initial `git merge --no-edit feat/model-forge`: already up to date.

All seven items have five visually inspected rounds: blockout → shapes → details → texture → polish.
Each round retains model/atlas snapshots, front/side/top/iso, GUI, both first-person hands,
both third-person hands, ground/fixed, a comparison sheet containing the **image-generated target**,
and validation/view diagnostics. Each comparison sheet was opened before the next refinement;
observed differences and resulting edits are recorded in the item's `review.md`.

| Item | Rounds | Elements | Verdict | Main remaining weakness | Item commit |
|---|---:|---:|---|---|---|
| [fire-staff](tools/model-forge/work/fire-staff/final-sheet.png) REDO | 5 | 113 | Accepted local cuboid interpretation | Squarer top footprint; opaque, static flame | `12bd21c9` |
| [knight-sword](tools/model-forge/work/knight-sword/final-sheet.png) | 5 | 108 | Accepted local cuboid interpretation | Gold brighter than reference; tiny engraving lost at 32px | `b2c49ed2` |
| [frost-staff](tools/model-forge/work/frost-staff/final-sheet.png) | 5 | 165 | Accepted local cuboid interpretation | Rectangular stepped crystal facets; no translucency | `ff464afd` |
| [thunder-staff](tools/model-forge/work/thunder-staff/final-sheet.png) | 5 | 146 | Accepted local cuboid interpretation | Orb somewhat flattened; prongs thinner than painting | `12b38ba1` |
| [verdant-staff](tools/model-forge/work/verdant-staff/final-sheet.png) | 5 | 153 | Accepted local cuboid interpretation | Branch loop more angular/symmetric; leaves thin edge-on | `604b7297` |
| [orrery-staff](tools/model-forge/work/orrery-staff/final-sheet.png) | 5 | 189 | Accepted local cuboid interpretation | Inclined ring visibly stepped; no orbital animation | `039739b6` |
| [ember-gauntlet](tools/model-forge/work/ember-gauntlet/final-sheet.png) | 5 | 145 | Accepted local cuboid interpretation | Rectilinear fingers/bracer; wearable alignment needs client review | `8e9b60ce` |

## Deliverables and checks

For every item:
- `content/models/items/<id>.model.json`, referencing `astera:items/<id>`.
- `content/textures/items/<id>.png`: 128×128, five-tone material ramps, pixel highlights/wear,
  grain or engraving, consistent 2 texels/model unit.
- `tools/model-forge/work/<id>/ai-target/concept.png`: saved built-in image-generation output.
- `tools/model-forge/work/<id>/{recipe.py,review.md,final-sheet.png,final-review.json}`.
- `tools/model-forge/work/<id>/r1` through `r5`: retained review evidence, not just final results.

Final validation reloaded all seven content models and their actual resource texture references.
All pass schema, coordinates, allowed rotations, UV range and density with
`validate(model, texture_map(model), budget=250)` from **tools/model-forge/validate.py**.
All remain below 200 elements. Every required display transform is stored, including both hands.
No clipped construction or display preview. Native GUI checks: 16, 32 and 64 pixels.
Gauntlet hand transforms point the fist along the forearm direction; staffs/sword remain upright.

Shared `tools/model-forge/*.py` files were not modified. Item-local `fire-staff/forge_local.py`
reuses model-forge raster rendering, validation, display defaults and tile composition; recipes
for this batch import it. `fire-staff/publish.py` copies reviewed round assets and revalidates
real output references. It does not generate or score a construction-diagram target.

The existing CLI defaults to a 64-element budget and polygon-based comparisons, so its stock
`render/finalize` gate is unsuitable for the founder's new 80–200-element/generated-image brief.
The supported validator budget argument is used explicitly; schema/density checks are unchanged.
No numerical IoU pass is claimed. Visual target fidelity is the recorded gate.

## Generation and reference provenance

Used the **built-in image generation tool**, never a paid/API fallback or external modelling service.
Read every item YAML, the weapons-v1 description spec, and the source manifest; visually inspected
`content/textures/sources/sheets/weapons-v1.png` before authoring. Original four final models/sheets
were read as baseline examples. No model-derived drawing was used as a target.

Generation prompt set: premium detailed Blockbench/cuboid fantasy prop; same-scale front, side,
axial top and three-quarter views; flat magenta key; rich hand-painted pixel ramps; no shadow,
particles or aura. Item-specific prompts:

| Item | Source cell (row, column) | Prompt identity |
|---|---|---|
| fire-staff | 2, 2 | Twisted warm wood, three-prong gold cradle, asymmetric red-orange flame crystal with yellow core, ruby fittings |
| knight-sword | 3, 1 | Plain steel double-edge/fullered blade, gold upswept quillons and wheel pommel, brown leather spiral grip, no magical gem |
| frost-staff | 2, 3 | Tall cyan-blue ice spear and unequal side shards, bronze roots, silver bindings, dark wood, ice pommel |
| thunder-staff | 4, 3 | Purple faceted orb, bold raised yellow lightning bolt, swept gold prongs, amethyst fittings and dark wood |
| verdant-staff | 4, 5 | Open organic wood loop around green seed, emerald pointed leaves, vines, sparse bronze, exposed root foot |
| orrery-staff | 3, 2 | Volumetric intersecting gold armillary bands, purple core, pinned cyan planets, gold wire/collars and finials |
| ember-gauntlet | 2, 1 | Soot-black clenched fist, four heated knuckles, folded thumb, vented shield, layered bracer, hollow cuff and bronze rivets |

Fire, frost and thunder first generations had incorrect top panels; additional built-in image edits
corrected those **before** modelling. Some other generated top panels retain illustrative elevation
bias, documented in item reviews. Their identities/proportions/materials are visual references, not
calibrated camera masks. The old fire-staff `concept.json` / `concept-sheet.png` from the merged baseline
are legacy construction references, superseded for this redo by `ai-target/concept.png`.

## Tool ideas / limits

- Add CLI `--budget` and raster-image reference panels without modifying the shared code mid-batch.
- Allow explicit reference-view crops/order and camera metadata; generated sheets sometimes reorder
  views or draw an elevation instead of a true top view. Keep those defects visible, never fake IoU.
- Offer a hand/forearm proxy and actual perspective display camera. Current previews show stored TRS
  in isolation, so real client grip, left-hand thumb position, swing/use motion, FOV, glint and z-fighting
  are **not certified**. No pack/client was run.
- Add per-material UV start controls to avoid tiny facets all sampling the brightest edge of a trim.
- Keep inclined ring authoring within one-axis rotation limits; a smoother approximation needs more
  segments or a broader accepted format profile, not arbitrary unsupported rotations.

No Gradle, Docker, tests, push, CI, paid services or runtime/build changes were used.
Unattributed untracked `knight-sword/concept.json` and `concept-sheet.png` were left untouched and
were not used as targets or added to these commits. This report is the handoff; `.claude/` harness
files remain untouched per task scope.
