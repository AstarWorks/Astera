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
5. **`move:` keys are coefficients of the frame's declared axes.** The origin path is `origin + v·c_v + w·c_w + s·c_s` on the
   axes the frame declares (`forward`, `up`, `v x w`), before any `rotate` / `scale` key; the turned basis rotates about that
   origin. ADR-0314 applied the move along the turned axes, which made a hand path depend on every rotate key before it (the
   lift of the upright blade followed by a roll about v threw the origin 1.9 blocks sideways on the roll tick). Amends the
   `move` clause of ADR-0314; `rotate` and `scale` are unchanged. The only other shipped `move` user, `revolver-shot`
   `gun`, is re-rooted by item 9 below, so no shipped skill keeps the old reading.
6. **The puppets are pushed at the end of the tick, on the caster the frame table saw.** `pushBodies` runs after the rows
   and the riders of the tick, so a rig that starts on a tick is pushed on the tick its mesh is first written (the drivers
   step ran before the rows, which left the cast tick without a body). The puppet anchor (feet, yaw, pitch) and the pinned
   hand target are computed on the caster cells snapshotted right before `evaluateFrames` (`Run.tableFeet/tableRot/tableLook`),
   not on a live read at the end of the tick: a dash that starts in this tick's rows moves the caster after the frame table
   and the mesh were placed, and reading the moved caster would put the body one dash step ahead of its own sword on that tick.
   Body, hand target and mesh move together on the next tick.
7. **Step keys begin at `at`.** `FrameGrammar` tracks hold the previous value for `t < at` and begin at `t == at`; a
   `for: 0` key is its value from its own tick on (so a `{at: 0, for: 0}` move places a frame on the cast tick).
   Previously a `for: 0` key applied one tick late. The only shipped `for`-less key, `bastion-greatsword-s2`
   `{at: 67, reset: true}`, now resets at tick 67 instead of 68.
8. **Verification in the sim.** `FrameRigScenarioTest.thePuppetGripSitsOnTheBladeFrameOriginEveryTick`: the pushed
   frame's held-item grip, placed by the pushed anchor, is within 0.05 blocks of the mesh grip on every tick the mesh is
   written; reach beyond ±0.02 is printed per tick. `theEdgeLeadsOnEveryMovingTick` is unchanged (off-plane < 15° on
   every tick with tip speed > 0.25 blocks/tick before the reset).
9. **A frame rig needs no body clip: the frame alone can pose the arm.** `{op: rig, frame: <id>}` without `anim:` puts
   the puppet up at `SkinRigAnimation.REST_BODY` (`IGameplayGateway.showBody` / `hideBody`, beside `playBodyClip` /
   `stopBodyClip`) and pushes the rest body with the carrying hand pinned onto the frame origin every tick (`RigFrame.REST`
   + `rightHandTarget`). `REST_BODY` has no tracks and `lockMovement = false`: the pose is the frame's, the feet are the
   player's. A skill whose only body motion is "the hand is here" (an aimed pistol, a held tool) writes no clip.
10. **The pistol is aimed by its frame.** `revolver-shot` `gun` is rooted at `caster.eye` on the orthonormal aim basis
    `v: aim, s: right, w: s x v` (the up perpendicular to the aim: the hand pitches with the look, and its distance from the
    eye is the same at every pitch, which `w: up` is not; check 16 at pose (37, −20) read 1.95 with world up) at
    `{v: 0.57, w: -0.51, s: 0.30}`: the right arm straight out from the shoulder, 17° down, the barrel (0.27 above the grip
    in `content/frames/revolver.yaml`, new) 0.24 under the eye line. Not on the eye line: the caster-eye display coverage
    check (check 16, limit 1.5 = scale² / distance², 4 ticks sustained) wants the 0.94-scale mesh at least 0.77 from the
    eye, the straight arm reaches 0.60 from a shoulder joint 0.44 from the eye, so the only aimed points that pass are low
    and far (at `w: -0.30` the coverage is 1.82). The rig rides it with no clip (item 9), so the right arm is turned onto
    that point by look-at and the gun points along `aim`. The arm is one rigid bone: every pinned point must lie on the
    0.60 sphere about the shoulder joint (the sim printed −0.04..−0.05 reach and a 0.05 grip error for a 0.03 pull-back), so
    recoil is a swing on that sphere, not a pull-back. Recoil is the frame's own keys: `move:` to `{v: 0.59, w: -0.46}` (the
    arm 5° up) over 2 ticks and back over 4 (coverage peaks at 1.35), `rotate: about: s` to 20° and back. The flash and the bolt are the muzzle point of the same
    frame (`pos: {v: 0.65, w: 0.27}` = the item's muzzle from its grip scaled by PLAYER_SCALE), not `v: 0.7` of the hand.
    The cast ends on `bolt.end` (tick 1 when the bolt hits at once), so the rig is `detach: true` and `machine.tail` is 10
    (was 4): the aimed pose outlives the cast for the rig's 10 ticks. `FrameRigScenarioTest.thePuppetGripSitsOnTheRevolverGunFrameOriginEveryTick`
    checks every pushed body is the rest pose plus the pin, the pin is within the straight arm's reach (no clamp) and at
    the frame's point, the mesh grip is on it on all 10 ticks, and `showBody` / `hideBody` bracket the rig.

## Consequences

- One clock, one yaw, one anchor: the body the player sees is the body the particles and the mesh were computed from.
  The one-tick anchor lag is gone at the root (anchor and transformation written in the same `pose` call, from the same tick); no offset or
  roll tuning anywhere.
- Skill authors write where the hand is (the frame's `move:`) and how the blade turns (`rotate:` about its own axes);
  the arm is derived. Right-arm euler tracks in body clips are dead for frame-ridden rigs.
- Reach is a hard constraint the author sees: the solver prints per-tick reach, the sim prints clamped ticks. If a hand
  path needs a point beyond the arm the answer is a different path, not a longer arm. Measured on the shipped s1
  (2026-11-01): the author script pulled the chord between beats back inside the reach sphere on t4 (0.023), t46
  (0.032), t48 (0.123) and t50 (0.030 blocks); the sim then clamps no tick, puppet grip vs blade origin worst 0.011
  blocks (t8), RigAttach worst 0.011.
- The `GatewayRecorder` keeps `pushedBody` out of the oracle `calls`, so goldens do not change for the push itself; s1's
  golden changes with its new keys (3464 rows); s2's golden is unchanged by the reset-tick rule (its `reset` at 67 eases
  from the same pose the old 68 did).
- `content/vfx/bastion-s1-rig.yaml` was already deleted (commit `0c9e986ef`); `author_bastion_s2.py`,
  `tools/model-forge/work/bastion-greatsword/{rig_parts_s1,handover_s1}.py` and `tools/item-art/scripts/*` still
  reference it. Pre-existing, out of this ADR's scope.
- Frames read from `caster.hand_r` without a pinned rig read the authored arm (the rest pose when the skill has no body
  clip): a pistol frame rooted there put the flash at the hanging rest hand, 42° below the eye line in the caster capture
  (2026-11-01). Hence item 10: a weapon that must point somewhere is rooted in the eye / aim basis and the hand follows it.
- `revolver-shot`'s golden changes (the `gun` origin, the flash / bolt position, `body=` / `body_stop=` rows, the 10 rig
  pose rows; `tail` 4→10). Meaning changes for the founder to confirm: `tail` 4→10 (nothing else in the skill is tailed
  past tick 10), muzzle at `(v 0.65, w 0.27)` from the grip instead of `v 0.7`, `REST_BODY.lockMovement = false` (the s1
  clip locks movement, the pistol stance does not).
