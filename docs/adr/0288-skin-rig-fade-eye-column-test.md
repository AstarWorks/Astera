# ADR-0288: Skin-rig fade uses a camera-relative eye-column test

- Status: Accepted
- Extends: ADR-0285 (the sphere fade is replaced for head, body and legs; arms keep the sphere)

## Context

ADR-0285 faded rig bones inside a camera-distance sphere (0.6 to 1.2 blocks). The founder reported on 2026-10-27 that the rig fades when it should not, for example with blocks near the head. A sphere of 1.2 blocks is larger than vanilla F5 camera collision allows, so a wall or ceiling near the player pulls the camera inside the sphere. A bystander 1 block away also falls inside it.

## Decision

1. The vertex shader exports `rigCamOffset = transpose(mat3(ModelViewMat)) * rigViewPos`, the bone-to-camera offset in world-aligned axes.
2. Non-ghost rig fragments whose face points away from the camera are discarded (`dot(rigViewNormal, rigViewPos) > 0`). This hides a cube exactly when the camera is inside it, as a whole. `rigViewPos` is per fragment, not per bone, so any distance test on it cuts a cube partially (slabs, visible inner faces); the vanilla entity pipeline does not cull these faces.
3. Head, body and legs then use a hard cut (no dither, no translucency) when the camera is in a thin column around the bone: horizontal distance under 0.12 (head), 0.28 (body), 0.26 (legs), vertically within 0.6 above or 1.1 below the bone (legs have no lower bound). Larger radii bit slabs out of the head in F5 with a wall behind. Arms keep the small sphere fade (0.1 to 0.35). Constants sit at the top of `entity.fsh.patch`.
4. The first-person smear after the animation (round 1 haze) was the dither band on head/torso fragments at 0.36-0.46; the hard cut plus back-face discard removes it.
5. Own-rig-only fading (per-viewer bit) is deferred. All 16 sky-light codes are used, other block-light markers cause false positives near torches, 15 is forbidden by `PackValidationTest`, and Paper has no per-viewer metadata API. Duplicating entities per viewer would double the entity count. The tight column is below the 0.6 minimum distance between player hitboxes, so a bystander rarely triggers it.

## Consequences

- F5 with a wall behind, a low ceiling, or a wall in front no longer fades the rig from distance alone. The camera must be inside the column.
- Open: a camera squeezed to under about 0.28 horizontally from the body (a 1-wide corridor in F5) hides the torso/legs; arms in the 0.1-0.35 sphere still fade. Another player standing in the column fades that player's rig too.
- Harness: `make rig-fade-shot` gained cases (std, wall-back, ceiling, wall-front, bystander, ghost) selected via `CASES`.
