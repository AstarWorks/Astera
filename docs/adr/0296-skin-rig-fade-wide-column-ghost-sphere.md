# ADR-0296: Skin-rig fade: wider own-body column, ghost sphere

- Status: Accepted
- Supersedes: ADR-0293 decision 3 (column radii and window) and its ghost handling

## Context

The founder's playtest of ADR-0293 (2026-10-28) showed first-person obstruction by the animated rig: a violet ghost chunk ahead of the camera and a grey strip at the screen edge. Two causes. Ghost bones passed through the same narrow column test as the body, so an afterimage spawned at the caster overlapped the camera. And the radii (0.12 / 0.28 / 0.26) are below what animation and dash offsets move the bones relative to the eye.

## Decision

1. Ghost bones (`rigPart >= 16`) fade by a camera-centred sphere (0.6 to 1.5 blocks) for every viewer. They are decorative, so fading near any camera is acceptable.
2. Head, body and legs keep the hard cut and the back-face discard, with wider radii: head 0.4, body 0.45, legs 0.45; the window is 0.8 above and 1.9 below the camera (legs unbounded below). A bystander's nearest surface is at least 0.32 horizontally from a camera, so only a sliver of an adjacent bystander's near edge can fall inside.
3. Arms: sphere 0.15 to 0.5.

## Rejected

A per-viewer "own rig" bit (owner-only copy with a distinct marker, so only the viewer's own rig is ever hidden). The 16-code sky payload is full (bones, slim, ghost) and block-light markers other than 14 collide with natural light near torches. A compressed alphabet (slim detected from skin alpha, ghost limbs merged) plus an owner/public dual copy in `PaperDisplayEntityGateway` is feasible but also has to rework `PaperPlayerPresentation` reveal/release, which acts on one display id. Left for a follow-up if the geometric rule proves insufficient.

## Consequences

A camera jammed against the own head in F5 hides the body (accepted). The residual risk is a bystander's camera within about 0.45 blocks horizontally of another player's body.
