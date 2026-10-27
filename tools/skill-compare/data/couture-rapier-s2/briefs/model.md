# Brief: model, Couturier S2 body piece (pivot-only) + blade-a/blade-b without the pivot

Understanding: tools/skill-compare/data/couture-rapier-s2/beat-sheet/understanding-body.md
Read it first (rows 1-3 and the 分からないこと section). Read docs/contributing/vfx-team/modeler.md and tools/model-forge/AGENT.md.

## Goal
In S2 the held shears (item model `astera:couture-rapier`, 108 elements: a_* half + b_* half) split overhead into three separate flying objects: a BODY and two BLADES. Today the body is `couture-shears-linked`, which already contains two blades, so the enemy shows four blade shapes. Build the body as a pivot-only piece and make the blades blade-only, so that:
1. body + blade-a + blade-b, placed with the same transform and the same coordinates, rebuild exactly the held `couture-rapier` silhouette (one pair of shears, the founder's item 1);
2. each of the three is a readable separate object when it flies alone.

## Files you touch
- NEW `content/models/items/couture-s2-body.model.json` (+ manifest entry in `content/models/manifest.json`, id `items/couture-s2-body`, same format as the neighbours).
- EDIT `content/models/items/couture-rapier-blade-a.model.json` and `couture-rapier-blade-b.model.json` (remove what moves into the body; keep the full-model coordinates, do not recentre).
- Do NOT edit `couture-rapier.model.json` (the held model stays as it is), `couture-shears-linked`, `couture-rapier-open-*`, `couture-rapier-hidden`, or anything under content/vfx, content/skills or tools/skill-compare.

## Proposed split (from the current element names; confirm against the geometry)
- BODY = flower-ball pivot and ring handles: a_hub, a_hub_diamond, a_stud*, a_petal_*, a_heart, a_bead (and the b_ twins), plus a_ring_*, a_trim*, a_trimin*, a_neck*, a_wool* (and b_ twins). A single piece with the two ring handles hanging from the flower pivot.
- BLADES = a_blade*, a_ridge*, a_edge*, a_tip, a_edge_base only (and b_ twins). Each blade is a long single piece.
- If the ring handles read better on the blades (the blade then ends in its ring), say so in your report with a render of both options; the director decides. Default: handles on the body.

## Acceptance
- Overlay check: render body + blade-a + blade-b together with the same transform as `couture-rapier`; element-by-element they equal the 108 elements of `couture-rapier` (count them: body + a + b = 108, no duplicates, no invisible cubes added to hit a count).
- Each of the three renders alone from the side and the top with a readable silhouette (blade: long spiked blade; body: ring handles + flower ball).
- Colours unchanged (dark violet + the existing petal/heart colours).
- Texture: no new derived Mojang PNGs; use the existing couture textures.
- Report: element counts per file, the two overlay renders (path), and what you moved.

## Do NOT
- Do not touch the held model or its open-* states.
- Do not recentre the blade models: the rig and projectile VFX position them by the full-model coordinates.
- Do not add a third blade or any extra piece.

## Applicable Rejected looks
Blocky single-colour props, weak silhouettes, jagged low-res textures (docs/contributing/vfx-art-direction.md).
