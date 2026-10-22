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
