# All-angle reviewer — 2026-10-23

Input: authored item JSON and PNGs at main baseline `414a896e`, not the prior
readability snapshot. No model/texture edits or pack deployment in this change.
Per-model content hashes and candidate counts are in `summary.json`.

## Evidence

- Built one 6.2MB standalone HTML containing all 21 models and their actual atlases.
  Opened it through `file://` in local Chromium. Zero external requests.
- Rendered 26 orbit directions and 9 display contexts for every model: 735 PNGs,
  no browser errors. Opened all 21 contact sheets retained here, not just logs.
- Exercised UI model/context dropdowns, candidate-pair selection/focus, mouse orbit,
  xray, element isolation, saved-camera restore, and first-person left-hand FOV.
  Unknown element IDs are rejected. The original fixed-board command also ran
  successfully after renderer extraction; it shares the same geometry loader.
- Additional arbitrary-angle rifle capture: yaw 127, pitch 18, zoom 1.5,
  wireframe, e0001. Examined axe e0003/south versus e0006/south in close-up,
  both with colored candidates and original texture. Pair colors intentionally
  show complete face rectangles, including any transparent texture region.
- Opened the full UI, underside, pair and detail screenshots during review.
  `pair-ui.png` and `viewer-ui.png` preserve representative tool evidence.

## Visual observations, not an asset pass certificate

- Back/underside surfaces are rendered and accessible. Gun end-on silhouettes,
  gauntlet cuff opening and staff-head gaps can be inspected from either side.
- The baseline's newer couture-rapier (scissors) and edict-blade (fist) assets differ
  from the earlier readability pass. At this viewer's reference first-person
  camera, scissors blades extend outside the image and the fist is mostly below
  the image. These are exposed display-review findings, not repaired here.
- The current source produces 345 exact same-facing projected-overlap candidates:
  couture-rapier 58, crimson-chain-axe 271, edict-blade 16. Hidden faces are counted;
  this is not a claim of 345 visible flickers. The other 18 have zero exact pairs
  at this threshold, but near-parallel candidates remain (including deliberate
  0.02-unit layers). Zero exact pairs is not a visual quality guarantee.
- The axe close-up exposes stepped overlapping rectangles suitable for an actual
  surface-cleanup decision. A fully covered face can be omitted; partial coverage
  requires splitting/restructuring rather than blindly deleting the whole face.
  This tool makes that decision inspectable; it never applies an automatic fix.

## Limits

Reference lighting, opaque/cutout materials and a rest-pose mannequin are used.
No Minecraft client was started; no live depth-buffer precision, AO, shader packs,
glint, swing/use or motion-dependent flicker is certified. OrbitControls clamps
poles by a small epsilon. Free camera movement is continuous; the automatic sweep
is finite. No unit tests, paid APIs or CI were used. Actual render/interaction
runs are the validation evidence. Raw captures and per-camera metadata were
written to `/tmp/astera-reviewer/all` during this session; the committed contact
sheets and hash summary are durable evidence.
