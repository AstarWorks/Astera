# ADR-0298: Skin-rig marker needs a winding confirmation

- Status: Accepted
- Amends: ADR-0250 (marker), ADR-0285 (fade). Their light-marker decode stays; it is now a candidate test.

## Context

Founder, 2026-10-28: near a lantern the whole body vanishes, for the player and for other players and mobs. Cause: the rig marker is block light 14 with the sky nibble as payload (`UV2.x == 224`, `UV2.y & 15 == 0`). World light produces exactly that next to every light-15 source (lantern, glowstone, sea lantern) and inside a torch cell, with any sky value. Every ordinary entity there was decoded as a rig bone: its UVs were remapped with the wrong bone (discard), faded by camera distance, and forced fullbright. Display brightness is 0-15 per channel, the skull renderer has no tint and overlay is fixed, so no light value is unreachable by natural light. A light-only marker can never be unforgeable.

Reproduction (round-1 shader, real client): `build/vfx-shot/rig-fade-lights-before/` against the `control` station (no light source, all bodies drawn). Lantern, light-14 and light-15 stations: bodies of player, zombie and villager gone, held items left.

## Decision

1. The light marker is a candidate only (`rigPart >= 0`).
2. The `astera:skinrig` item model is mirrored in x (`scale [-1, 1, 1]`). A rig face that looks at the camera is therefore a back face (`gl_FrontFacing == false`) while its vertex normal points at the camera. The fragment shader confirms `rigConfirmed = gl_FrontFacing != (dot(viewNormal, viewPos) < 0)`. A real entity's front faces always agree with their normals, so it is never confirmed.
3. Until confirmed, the fragment keeps the entity's own light (`lightMapColor`) and its ordinary shading; the fullbright rig light travels in `rigLightColor`. Fade, discard, UV remap, ghost tint, rim and luma apply only when confirmed. `rigRemap` takes the mirror partner (left/right faces swap, u reversed) to undo the mirror, and a confirmed rig picks the front vertex colour.
4. Round-1 constants, ghost look and first-person hiding are unchanged.

## Unforgeable and residual

A natural entity cannot be confirmed without a mirrored (negative determinant) model matrix in a block-14 cell. Vanilla has such a case (left-hand held item display); it shows as a remapped, faded item at worst, in a 1-in-256 light combination, instead of a whole body.

## Rejected

Round-3 wider fade radii (separate ADR draft, dropped): they hide more and do not touch this cause. A different marker light value: every value 0-15 is produced by natural light.
