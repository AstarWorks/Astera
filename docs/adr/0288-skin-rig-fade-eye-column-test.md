# ADR-0288: Skin-rig fade uses a camera-relative eye-column test

- Status: Accepted
- Extends: ADR-0285 (the sphere fade is replaced for head, body and legs; arms keep the sphere)

## Context

ADR-0285 faded rig bones inside a camera-distance sphere (0.6 to 1.2 blocks). The founder reported on 2026-10-27 that the rig fades when it should not, for example with blocks near the head. A sphere of 1.2 blocks is larger than vanilla F5 camera collision allows, so a wall or ceiling near the player pulls the camera inside the sphere. A bystander 1 block away also falls inside it.

## Decision

1. The vertex shader exports `rigCamOffset = transpose(mat3(ModelViewMat)) * rigViewPos`, the bone-to-camera offset in world-aligned axes.
2. Head, body and legs fade only when the camera is in a thin column around the bone: horizontal distance under 0.36 to 0.46 (head), 0.30 to 0.42 (body), 0.30 to 0.40 (legs), and vertically within 0.5 above or 1.0 below the bone. Arms keep the small sphere (0.1 to 0.35). All constants sit at the top of `entity.fsh.patch`.
3. Own-rig-only fading (per-viewer bit) is deferred. All 16 sky-light codes are used, other block-light markers cause false positives near torches, 15 is forbidden by `PackValidationTest`, and Paper has no per-viewer metadata API. Duplicating entities per viewer would double the entity count. The tight column (0.46) is below the 0.6 minimum distance between player hitboxes, so a bystander rarely triggers it.

## Consequences

- F5 with a wall behind, a low ceiling, or a wall in front no longer fades the rig from distance alone. The camera must be inside the column.
- Open: a camera squeezed to under 0.45 horizontally (for example a 1-wide corridor in F5) still fades the head. Another player standing in the column fades that player's rig too.
- Harness: `make rig-fade-shot` gained cases (std, wall-back, ceiling, wall-front, bystander, ghost) selected via `CASES`.
