---
status: Accepted
date: 2026-11-01
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0315: Driven puppet: the engine poses the skin rig per tick and the hand follows the weapon frame

## Status

Accepted

## Context

ADR-0314 made the weapon frame (`frames: blade`, origin + v, w, s) the single basis particles, displays and the held mesh
are drawn from. The skin-rig puppet (the body displays that replace the caster's own model during a cast) still ran on its
own: `PaperSkinRigAnimator` sampled the body clip on its own scheduler tick, estimated the body yaw itself and moved its
anchor from a player location read on that tick, a tick behind the engine. The weapon frame read `caster.hand_r` from the engine's own sample of
the same clip. Two clocks, two yaws and a one-tick anchor lag produced frame_006 (arm raised, sword at the waist) and
frame_009 (sword out of the hand) in the s1 captures.

The dependency was also backwards. `bastion-greatsword-s1` authored the right arm (euler keys in
`content/animations/bastion-s1-body.yaml`), hung the blade frame on the hand it produced, and then needed six `about: v`
compensation keys so the blade's edge stayed in the cut plane while the arm wandered. The founder's reading of the
captures: 「剣がなんか平たい所で殴る」. The founder's direction: one basis for particles, VFX and the skin animation
(「パーティクル、VFX、スキンアニメ、それぞれで使う基準を共通化…計算も減る」) and swings authored as axis turns and
origin moves (「基軸回転を入れたり、originの移動とかも普通にできれば」).

## Decision

1. **The puppet is driven, never free-running.** `ISkinRigPuppet` (`show` / `pose(player, RigFrame, RigAnchor?)` /
   `hide`) is the only body output. The table player pushes one `poseBody` per engine tick with the exact `RigFrame` it
   used for `evaluateFrames` on that tick (clip tick = now − start, the caster's look yaw, the engine-tick feet) and the
   Paper puppet moves its displays in `pose` (`teleportAsync`, the only teleport a Paper adapter may use; within one loaded world
   it completes before it returns, and a move still pending at return is counted and logged) and writes the transformation in the same call
   (`teleport_duration = 1`, `interpolation_duration = 1`). The free-running `PaperSkinRigAnimator` /
   `InMemorySkinRigAnimator` are deleted; `ISkinRigAnimator` (`play` / `stop` / `active`) survives only as the clip
   player for non-skill animations (`SkinRigClipPlayer`, which also drives the puppet, tick by tick, and yields when a
   skill owns the player's body).
2. **The right hand is placed by look-at IK onto the weapon frame origin.** `RigFrame.rightHandTarget` (rig-local) is
   set by the engine from the frame the held rig rides (`pinnedHandTarget`: frame origin − feet, rotated by the
   caster's inverse yaw). `SkinRigTransforms.rightArmRot` rotates the authored arm so its grip ray points at the target
   (`rotationTo(gripAuthored, dirLocal) · authored`), the mirror of `leftArmRot`. Reach is reported, not hidden:
   `rightHandReach` = |target − joint| − |grip| in blocks; a target the straight arm cannot reach is clamped on the arm's
   ray and the sim lists the tick.
3. **The frame leads, the hand follows.** A held rig riding a frame with `origin: caster` moves by the frame's `move:`
   keys; `handOrigin` of the pinned hand returns that frame's origin, and a frame whose origin is `caster.<hand>` of the
   hand pinned to it is a compile-time circularity fault. Body clips no longer carry a right-arm track for such skills;
   `tools/pose-solve/author_bastion_s1.py` solves the body beats and writes the hand path as the blade's `move:` block
   (the solved grip of every beat plus a key on every tick the chord between beats leaves the arm's reach sphere).
4. **s1 is authored as moves and cuts only.** The six `about: v` compensation keys are deleted. The blade lifts upright
   (ticks 0–2, in the blade plane), rolls once about v with the hand still (2–3), then every cut is a turn about s.
   Authoring rule that follows from intrinsic composition: a roll about v while the blade is already turned off v by an
   s-key moves the tip exactly along the plane normal, so a roll finishes before the next s-turn starts and the hand is
   still during it.
5. **Step keys begin at `at`.** `FrameGrammar` tracks hold the previous value for `t < at` and begin at `t == at`; a
   `for: 0` key is its value from its own tick on (so a `{at: 0, for: 0}` move places a frame on the cast tick).
   Previously a `for: 0` key applied one tick late. The only shipped `for`-less key, `bastion-greatsword-s2`
   `{at: 67, reset: true}`, now resets at tick 67 instead of 68.
6. **Verification in the sim.** `FrameRigScenarioTest.thePuppetGripSitsOnTheBladeFrameOriginEveryTick`: the pushed
   frame's held-item grip, placed by the pushed anchor, is within 0.05 blocks of the mesh grip on every tick the mesh is
   written; reach beyond ±0.02 is printed per tick. `theEdgeLeadsOnEveryMovingTick` is unchanged (off-plane < 15° on
   every tick with tip speed > 0.25 blocks/tick before the reset).

## Consequences

- One clock, one yaw, one anchor: the body the player sees is the body the particles and the mesh were computed from.
  The one-tick anchor lag is gone at the root (anchor and transformation written in the same `pose` call, from the same tick); no offset or
  roll tuning anywhere.
- Skill authors write where the hand is (the frame's `move:`) and how the blade turns (`rotate:` about its own axes);
  the arm is derived. Right-arm euler tracks in body clips are dead for frame-ridden rigs.
- Reach is a hard constraint the author sees: the solver prints per-tick reach, the sim prints clamped ticks. If a hand
  path needs a point beyond the arm the answer is a different path, not a longer arm.
- The `GatewayRecorder` keeps `pushedBody` out of the oracle `calls`, so goldens do not change for the push itself; s1's
  golden changes with its new keys, s2's with the reset tick shift.
- `content/vfx/bastion-s1-rig.yaml` was already deleted (commit `0c9e986ef`); `author_bastion_s2.py`,
  `tools/model-forge/work/bastion-greatsword/{rig_parts_s1,handover_s1}.py` and `tools/item-art/scripts/*` still
  reference it. Pre-existing, out of this ADR's scope.
- Frames read from `caster.hand_r` without a pinned rig (`revolver-shot` `gun`) read the authored arm (the rest pose
  when the skill has no body clip); the flash sits where that arm's hand is.
