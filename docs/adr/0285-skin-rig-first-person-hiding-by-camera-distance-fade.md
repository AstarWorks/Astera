---
status: Accepted
date: 2026-10-27
deciders: founder, Claude
supersedes: null
superseded_by: null
---

# ADR-0285: First-person hiding of the skin rig is a client-side camera-distance fade in the shader

## Status

Accepted (PoC). Extends [[adr/0250-skin-rig-player-animations-display-rig-and-skin-uv-shader]] (the head-only "drop when
enclosing the camera" rule in `entity.vsh` is replaced) and [[adr/0278-skin-rig-spine-hierarchy-and-two-hand-grip]].

## Context

The rig is a set of Display entities that surround the caster. Seen from the caster's own first-person camera, the head and
torso fill the view. The caster should still see the whole animation in F5 (third person). The server does not know the
client's camera perspective: no packet carries it.

## Decision

The hiding is done on the client, in the resource-pack shader, by distance to the camera:

- `entity.vsh` passes the camera-space position of every rig fragment (`rigViewPos`, only for rig parts);
- `entity.fsh` discards rig fragments closer than `*_NEAR`, shows them fully from `*_FAR`, and uses a 4x4 Bayer ordered
  dither in between (the pass is cutout/opaque, so no alpha blending). Constants at the top of the patch:
  body/head/legs 0.6 -> 1.2 blocks, arms 0.1 -> 0.35 blocks (arms fade more gently, so the first-person swing feel stays);
- first person: the caster's rig overlaps the camera, hidden. F5: the camera is 3+ blocks away, fully visible. Other
  players' rigs are practically never within 1 block of your camera, so they look unchanged. Non-rig geometry is untouched.

The server is unchanged. Fallback if the shader route fails (shaderpacks, or a client that ignores the core override): hide the
rig entities from the caster per viewer (`Player.hideEntity` / `setVisibleByDefault(false)` + `showEntity` for the others) in
`PaperDisplayEntityGateway.spawnAttached` (`plugin/combat/.../adapter/paper/`). Not implemented: it would also hide the rig in
F5, which is what this ADR avoids.

## Consequences

- No server cost, no new packet, no per-viewer state.
- The held-item display (`THIRDPERSON_RIGHTHAND`) is not a rig part (no payload) and is not faded.
- Iris/Sodium shaderpacks replace the core entity shaders, so the fade does not run there: the rig stays always visible
  (blocks first person), not broken. Vanilla and Sodium without a shaderpack run the fade.
- A third-person camera zoomed to under ~1.2 blocks (mods) fades the body too.

## Alternatives Considered

- **Server-side `hideEntity` for the caster**: also hides the rig in F5 (rejected as the primary path).
- **Guess the perspective from packets**: none exist.
- **Head-only drop in the vertex shader** (previous rule): leaves torso/legs/arms blocking the view.

## Merge with ADR-0284 (ghost_body)

- ADR-0284's vertex-shader head-drop rule (`rigPart = -2`) is removed. The
  camera-distance fade already covers the head, so one mechanism handles
  first-person hiding. No `-2` sentinel remains in either shader.
- The fade classifies bones by `rigPart & 7`, so the slim (+8) and ghost
  (+16) flags do not affect it. `rigViewPos` is emitted for every rig part.
- Ghost bones fade near the camera too: an afterimage overlapping the camera
  blocks the view equally. The dither discard works in the translucent pass.
- Re-verified after the merge with `make rig-fade-shot` on
  `anim-demo-sword-swing` (no regression) and `couture-rapier-s2` (ghost tint
  and slim/ghost UV remap intact).

## Verification

`make rig-fade-shot` (real client, `tools/vfx-shot/rig-fade-shot.sh`, skill `anim-demo-sword-swing`, FOV effects off so
`lock_movement` does not zoom the capture), 2026-10-27, A/B against the origin/main shader (`OUT_TAG=base`):

- First person: the main-branch shader shows the swinging right arm filling the right quarter of the view (frames 12-14 of
  the baseline) with the older arm radii 0.25 -> 0.6 the arm vanished completely, so the radii were lowered to 0.1 -> 0.35:
  head, torso and legs never show, the arm still sweeps into view. The held-weapon display is not faded.
- F5 (back, vanilla 4 blocks): the whole swing shows the full rig with no holes.
- Bystander 2.4 blocks away: pose, twist and weapon identical to the baseline; the swing frames are clearly the rig (body
  twist), the frames before/after are the rig at rest, indistinguishable from the vanilla model.

Core shader: `core/entity.{vsh,fsh}` (player_head ItemDisplay, `astera:skinrig`) on 26.3.
