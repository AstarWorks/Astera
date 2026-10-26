---
status: Accepted
date: 2026-10-26
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0278: Skin-rig spine hierarchy and two-hand grip

## Status

Accepted. Amends [ADR-0250](0250-skin-rig-player-animations-display-rig-and-skin-uv-shader.md) (bone layout) and
[ADR-0276](0276-skill-performance-one-body-one-weapon-one-lease.md) (the weapon hangs from the right arm, which now
hangs from the spine). Their bodies stay unchanged; where they differ on the hierarchy, this ADR wins.

## Context

The ADR-0250 rig was six sibling bones under a hip-pivot `root`, with `body` rotating alone around the vanilla neck
joint (0, 24, 0). With no spine the torso could not pitch, yaw or roll on its own: a lean or twist either rotated only
the torso box (head and arms stayed upright and detached) or had to go on `root`, which tilts the legs too, so authors
re-aimed each leg to keep the feet planted. Deep crouches, forward leans and twisted coils could not be posed, and the
solved bodies (`bastion-s1-body`) kept `body` at zero.

## Decision

1. **Two-level hierarchy.** `root (hips) -> body (spine) -> {head, right_arm, left_arm}`; `root -> {right_leg, left_leg}`.
   `SkinRigBone.parent` is the single source; a child's rotation / position are in its parent's frame, around its own
   joint. The weapon of a performance (and the emote's held item) hangs from the right arm, so it follows the spine.
2. **Spine pivot at the waist** (0, 12, 0), the top of the legs, instead of the vanilla neck joint. Rotating `body`
   bends at the waist and carries the chest, head, arms and weapon; the legs stay. `root` keeps its hip pivot and
   translation, so a crouch is `root.position.y` about -6 px plus leg rotations.
3. **Two-hand grip** (`two_hand_grip: true` on an animation): the left arm's authored rotation is replaced by a
   look-at of its hanging axis toward the right-hand grip (the held-item origin), blended by the playback weight.
   The arm is one rigid bone, so the hand lands on the line to the grip at arm's length (10 px); it reaches the grip
   only when the grip is about that far from the left shoulder.
4. **Same math everywhere.** `SkinRigTransforms` (runtime + plan.json sampler, which the three.js preview renders) and
   `tools/pose-solve/rigmodel.py` implement the same chain; the Blockbench template nests head and arms under `body`
   and `anim-import` rejects any other nesting.
5. **Pre-release posture:** no shim. Existing animations keep their keys; a non-zero `body` rotation now also turns
   head and arms around the waist (the shipped ones are small demo / emote angles).

## Consequences

- Leans, twists and deep coils are authored on `body`; `root` rotation is for whole-body tilts only.
- `rigmodel.fk` gains a `spine` landmark and `plan.json` a `spine` bone; pose-solve scripts that leaned with `root`
  can move the lean to `body`.
- Static named poses live in `content/poses/<group>/<name>.yaml` (not loaded by the plugin yet);
  `tools/pose-solve/bake_pose.py` bakes one into a scratch performance for capture and preview, and an intent's
  `camera_presets` gives vfx-shot (`CAMERA=<name>`) and skill-preview (`--camera <name>`) the same cameras.
- Still not modelled: knees and elbows (one rigid box per limb, so a deep crouch is a split stance), and a real
  2-bone IK for the off hand.
