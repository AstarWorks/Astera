# Inventory readability and coplanar-face polish — 2026-10-23

Scope: all 21 existing held-item models. Forge v2 was integrated on the isolated
`codex/model-readability` branch before this pass. No textures, hand display
transforms, plugin code, or item definitions changed.

## Critic

- Inventory: long guns waste vertical space; most props are small and off-center.
  Set GUI X/Y rotations to 10/-15 degrees, Z to +35 for guns and -35 for other
  props; center each projected bounding box and store a uniform scale that fits
  a 14-unit square inside the 16-unit GUI viewport. Smaller yaw exposes the broad
  face. The fit is an authoring calculation, not runtime camera auto-fit.
- Joins: exact coplanar, same-facing overlaps exist on blade bevels, grip wraps,
  barrel facets and ornaments. Offset conflicting face boundaries outwards in
  0.02-unit layers; keep larger base faces at their original plane where possible.
  Maximum cumulative coordinate change is 0.06 units (0.00375 blocks). Opposite
  normals at ordinary solid-to-solid contacts are deliberately excluded.
- Preserve established palette, silhouettes and hand placement. No new detail.

## Verifier

- Project API import/snapshot/diff/validate was used before and after each asset;
  all 21 pass schema/coordinate/texture-reference validation. Temporary full
  snapshots and exact diffs are in `/tmp/astera-model-polish/projects` and sibling
  `*-diff.json` files for this session. These temporary paths are not durable.
- Exact transformed-face coplanarity plus separating-axis rectangle overlap:
  3,013 same-facing overlapping pairs before, zero after. This includes hidden
  geometry; it is not a count of visible glitches. Reproduce the final check:
  `python3 tools/model-forge/batches/readability/check_coplanar.py`.
- Opened before/after inventory sheets at 16/32/64px and all after model boards.
  GUI silhouettes are larger/centered, gun diagonals use available space, and all
  GUI projections remain inside their frame. Rapier and staff details remain
  subpixel at 16px; the recognizable outline is the intended improvement.
- Reviewed front/side/top/iso/GUI/first-person/third-person/ground/fixed boards.
  No visible detached joins introduced; coordinate changes remain <=0.06 units.
  Non-GUI display transforms and textures are byte-value identical to the inputs.
- `report.json` records counts, transforms and largest coordinate changes per item.
  `before.png` / `after.png` are the inventory comparison; `boards-*.jpg` contain
  the final nine-view boards, with item names in normal sorted order.

Limit: the software renderer is orthographic, not Minecraft. It cannot certify
motion-dependent depth precision, client lighting or actual hand-camera framing.
Near-coplanar faces are not counted by this audit. Final in-game confirmation is
still needed from the founder; no Minecraft client or unit tests were launched.

## Follow-up: complete display contexts

The founder explicitly requested the complete item presentation, not mesh-only
completion. The earlier unchanged-hand observations above describe round one;
the final assets now also change first/third-person R/L, ground and fixed.

An offline three.js player-context renderer exposed defects hidden by isolated
orthographic views: rifles pointed downward in third person and held the wrong
part; first-person left/right swords were not mirrored as intended; gauntlet
fingers pointed back toward the arm. The new renderer uses Blockbench's player
base transforms and Java left-hand sign convention, with a labelled mannequin
and a perspective camera. This is now a reusable `context_preview.py` command.

- Each item's actual grip center anchors to the hand. Rifles compensate the
  -67.5-degree player rest-pose rotation, producing forward-pointing barrels;
  offhand yaw also preserves forward direction. Shotgun anchors at the stock
  neck, pistol at its raked grip, and long guns at their pistol grip.
- Swords/staves have an upright, forward-leaning third-person pose and category
  scales (dagger remains short, long weapons remain visibly long). First-person
  scale/placement keeps the screen center clear; left/right placement is mirrored.
- Gauntlet cuff surrounds the forearm and fingers extend past the hand, with the
  left-hand yaw placing the thumb on the opposite side.
- Ground models are centered around their spin axis, posed nearly horizontal and
  sized to an 11-unit longest dimension. Ground local minimum Y is zero; default
  reference float height is shown, not a claimed reproduction of client bobbing.
- Fixed models fit a 9.5-unit span inside the 10-unit frame opening after the
  engine's half-scale; centering and depth keep the model in front of its backing.
- Final GUI transforms and geometry remain unchanged from round one.

Opened all 21 final context boards and the full-size rifle, shotgun, greatsword
and gauntlet boards; compared original-context boards as well. The documented
CLI was exercised independently on rifle/gauntlet and again after extracting the
browser module. No unit tests, Minecraft client or GitHub CI were run.

`contexts/before` and `contexts/after` preserve all nine-view boards;
`contexts/report.json` records authored grip points and every display transform.
The earlier `boards-*.jpg` are round-one isolated previews, not final contexts.
The pipeline now requires context review for future changes. Rest-pose checks
cannot certify live swing/use/aim animation, all FOVs or shader-dependent flicker;
the founder's in-game check remains necessary.
