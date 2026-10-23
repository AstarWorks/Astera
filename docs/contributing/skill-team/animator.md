# Animator knowledge base

Agent: `.claude/agents/limbus-animator.md`. Team overview: [README.md](README.md).

## What the animator produces

1. Weapon rig swing keyframes in a VFX file's `rigs:` block (`swing`, with `pivot`, `rest`, `tilt`).
2. Caster movement vectors in the skill YAML (`dash_strike`, `propel`, `leap`, `set_velocity`), each as distance / ticks / direction.

## Principles (source: [vfx-art-direction.md](../vfx-art-direction.md) sections 0, 0.5, 7)

- Anticipation (wind-up) -> fast main motion -> overshoot and settle. The main motion finishes within the **first 1/4** of the lifetime. No linear timing; the aftermath weakens gradually.
- Hold the apex 1-2 ticks. The swing's **edge leads** the arc.
- Fast swings: one update per tick at least. The client interpolates linearly (translation) and by slerp (rotation) per update, so a 300 deg swing over 3 ticks is about 3 chunks of 100 deg, which the planner subdivides to chunks of at most **60 deg per tick**. An ease that exceeds 170 deg in one tick (for example `out_expo` for 300 deg in 3 ticks) makes slerp go the wrong way, and the planner throws. Use `out_quad` / `out_cubic` or add ticks.
- Hit stop: skill `hit_stop` plus `play_vfx` `freeze: [{ at, ticks }]` (effect tick). Rigs with `respect_hit_stop: true` pause swing and fade during the window; add the window to `duration_ticks`. Guide: light 1-2 t, heavy 3-4 t, finisher about 6 t (secondary sources).
- A horizontal true-side swing is unreadable; tilt the plane (`tilt`) 20-ish degrees. Do not put arcs exactly side-on or exactly head-on to the first-person camera.
- Weapon character: tip-strong = edge emphasis; greatsword = rough, heavy; blunt = juddering; beam = persistent glow; fire = afterimage of flame ([sakurai.md](../../reference/vfx/sakurai.md)).

## Rig syntax (verified in vfx-art-direction section 7)

```yaml
rigs:
  - id: sword
    pivot: [-0.35, 1.2, 0.3]   # hand position, anchor-relative (x=left, y=up, z=forward)
    rest: [0, 90, 0]           # pitch 90: blade (+Y) points forward (+Z)
    tilt: [0, 0, 20]
    respect_hit_stop: true
    swing:
      - { t: 0, rotate: [110, 0, 0] }
      - { t: 6, rotate: [-190, 0, 0], ease: out_quad }
```
Rigid per-part display = `pivot frame o tilt o swing o rest o part offset`; part boxes are specified by center `offset`, not corner. A rig effect must use `follow: false` (with `follow: true` the planes stick to the anchor and it no longer reads as a wind-cut). The sample is `content/vfx/demo-greatsword-swing.yaml`, played by `content/skills/vfx-demo-greatsword.yaml`.

Preview: `./gradlew :tools:vfx-preview:run --args='demo-greatsword-swing --view side|top|eye [--freeze 7:3]'` (`eye` = caster first person from y 1.62 along +Z). Output `build/vfx-preview/<id>/frame_NNN.png` and `sheet.png`. The preview replays client interpolation. It is not the real client: see pitfalls.

## Caster movement mechanics

Syntax reference: [add-a-skill-yaml.md](../add-a-skill-yaml.md) last section. Facts:
- `dash_strike`: moves the caster `distance` over `ticks`; targets touching the capsule (`radius`) run `on_hit`; `invulnerable` grants the `immunity` status.
- `hit_stop` and knockback / knockup / propel can be in any order; a push during the freeze applies at release.
- Mechanics with `delay_ticks` > 0, `period_ticks`, or loop stride re-resolve the caster's current position and facing at fire time (follows a dash). Use `snapshot_pose: true` to use the cast-time pose; `condition` evaluates at fire time.
- Round-trip and diagonal dashes: the procedure doc has sections on direction ("往復・斜めダッシュの方向") and conditional counters; read them in [limbus-skill-reproduction.md](../limbus-skill-reproduction.md).
- Hit overlaps the capsule: test with a target at two distances; the same contact tick as damage and VFX.

## Pitfalls

- A horizontally laid `fixed`-billboard TextDisplay renders in vfx-preview but **not** in the real client. Trails are quads/blocks; do not rely on laid text for the sweep.
- Hit VFX must peak on the damage tick. The rig swing is what the player reads, so its contact frame equals the damage tick; verify in the capture.
- Machine load causes 2-6 tick (sometimes ~20) lag; do not retune keyframes to a laggy capture. Reject frozen or magenta captures first.
- Rejected looks: separate floating sprites instead of one continuous sweep; a static ring standing like a wall; poles for jets.
- Software preview of motion is not proof of feel; the reviewer compares real-client frames.
- Display budget: rig trail panels = samples x (bands + edge + rim); about 50 Displays at `step_deg` 36 in the demo; planner rejects above `MAX_SLOTS` 256; whole cast <= 60 Displays by E2E measurement.

## Learnings log

(Append dated bullets after each skill. Format: `- YYYY-MM-DD <case>: <fact / pitfall / number>`.)

- 2026-10-23 seed: created from repo docs; no skill run yet.
- 2026-10-24 bastion-greatsword-s1 (round 1): a rig key `t=l` is DISPLAYED at effect tick `l+1` (+ frozen ticks before it; `VfxRigMotion.base = startTick + 1`). A `freeze {at: c}` stops the rig clock at key `c-1`, so frames c and c+1 both show key `c-1`: put the apex key at `c-1` for contact tick c. A second freeze shifts later keys by the first one's ticks (hit-2 apex key = 31 - 1 - 2).
- 2026-10-24 bastion-greatsword-s1: do NOT hand a sword over between separate rig effects (spawn of fresh displays reads as the weapon multiplying). Use ONE rig for the whole skill (hold -> swing -> hold -> swing -> hold) as `follow: true`; static holds = identical keys. Unwrap euler yaw continuously across swings (a 360 jump between two equal-quaternion keys would spin the sword).
- 2026-10-24 bastion-greatsword-s1: swing eulers are easiest to author by solving per-tick targets (tip phi/pitch + roll psi about the blade) in a scratch script: world matrix W = [edgeX, blade, face], S = tilt^-1 W rest^-1, decompose as Ry(-yaw) Rx(pitch) Rz(roll) (same as `Quaternion.fromEulerDegrees`, Hamilton product applies right-to-left). Verified with a test rig in preview: +X local edge shows to the caster's LEFT, negative yaw turns left, top-view forward = up.
- 2026-10-24 bastion-greatsword-s1: roll check = face normal (local Z) in anchor frame: REST sideways ((+-1,0,0)), sweep vertical ((0,+-1,0)). Edge-leading angle vs chord velocity at sample ticks cannot beat ~half the per-tick chord step (75-95 deg chunks give ~40); measure also at the slerp midpoint (about 20 or less).
- 2026-10-24 bastion-greatsword-s1: with the pivot ~0.9 above the floor and tip distance 2.55, any pitch below about -20 puts the tip under the floor; REST (-20) already sits at y 0.05. Brief pitch values -32/-40/-28 are not usable.
- 2026-10-24 bastion-greatsword-s1: trail budget by planner: intervals = sum over ticks of ceil(chunk_deg/step_deg) (chunk = quaternion angle, roll included); displays = intervals x (bands + edge + rim); all alive together for life_ticks. 2 bands + edge, step 40, ticks 2..6 of a 75/39/51/46 deg sweep = 21.
- 2026-10-24 tooling: the worktree-isolation guard rejects heredocs / loops / `cd &&` chains in Bash; use Write for files and one plain command per call. `python3 -I` drops the script dir from sys.path.
- 2026-10-24 bastion-greatsword-s1 (round 2): "rest alone" is not the rest look. Composed rotation = tilt * swing * rest, so the face normal at REST comes from the swing's roll keys; judge axes from tilt*swing*rest*localZ per key (small script on the yaml), not from `rest` by itself. `rest [0,90,0]` (pitch 90 maps blade +Y to forward, face Z to down) is fine; it is the standard base, the solved swing eulers add the sideways roll. Do not add `tilt [0,0,90]`.
- 2026-10-24 bastion-greatsword-s1: swapping placeholders for the real 20 parts needs no re-solve when the modeler keeps blade +Y / edge X / face Z and the tip distance (2.545 vs 2.55); only retune pivot height. REST tip y = pivot_y - 2.545*sin20 = pivot_y - 0.87 (pivot 1.0 -> 0.13).
- 2026-10-24 tooling: Bash guard in worktree agents refuses globs, loops, variable args to gradlew/montage/python. Run gradlew per view sequentially (parallel runs share build/vfx-preview/<id>) and build strips with a PIL script that hardcodes absolute paths.
- 2026-10-24 bastion-greatsword-s1 (round 3): a brief's "tip forward at contact" must be checked against the reference closeups (b02/b04/d01 here): when the blade is smeared into a ring, the real sword stayed behind and the ring did the cutting; a rigid tip-to-target pose reads as a stab in the real client. Author a pendulum behind the body instead (raise, chop, hold, overshoot past REST).
- 2026-10-24 bastion-greatsword-s1: a face-horizontal roll makes the blade a thin edge-on line in the side view; keep the face sideways when the swing plane is near vertical (edge up/down already leads). Solver: W = [edge, tip, face], edge = XREST projected off the tip, euler = decompose(tilt^-1 W rest^-1) as Ry(-yaw) Rx(p) Rz(r); auto edge-leading roll from chords was noisy (>90 deg flips between keys), use roll 0.
- 2026-10-24 bastion-greatsword-s1: freeze mapping from VfxRigMotion: key l is reached at tick l+1 before the first freeze, l+1+ticks after it (l+3, then l+5 after a second 2-tick freeze); the key at the freeze start holds. Rig duration_ticks caps despawn (freezes do not extend it): set it to the last tick the weapon must be visible + 1. The real client draws a newly spawned display 1 tick late.
- 2026-10-24 bastion-greatsword-s1 (round 4): compare-camera frame mapping comes from the case camera cuts: intent cut t=6 (close, camera at +z = caster's right) is the first zoomed frame, so compare tick 0 is ~2 frames earlier than the reviewer's "frame 9". A tip with phi 190-270 (caster's right) points at that camera and fills the frame; keep a "blade behind the body" rig at phi 140-180 (blade z <= 0.15 < body near face 0.3, so the torso occludes it as in the reference). Screen-right in the compare view = world +x (caster forward).
- 2026-10-24 bastion-greatsword-s1: solver needs no roll tuning: face = (-1,0,0) projected off the tip reproduces the REST euler [-172.9,18.7,68.8] exactly. Key every tick through fast parts; emit eased multi-tick settles as explicit per-tick keys. Raise 34 deg then slam 57 deg in one tick stays <= 60.
- 2026-10-24 bastion-greatsword-s1: held-item third-person pose (modeler evidence + side captures): blade up-forward ~50 deg above horizontal, broad face sideways. The rig ends by lifting REST -> overhead -> (phi 0, pitch +52) over keys 42-52 (ticks 47-57, in_out, 0.3 bow to the right to clear the head); residual pop is positional only (rig pivot right .15 / fwd .25 / y 1.0 vs the vanilla hand), not measured in the real client.
- 2026-10-24 bastion-greatsword-s1 (round 5): wind-up 2 kept low by replacing keys 24-27 (ticks 27-30) with a shallow pendulum: yaw 14.5/8.5/0.5/-10 and pitch -173.8/-175.2/-177.5/-181 (old raise 13.7/2.7/-14.8/-36.6, pitch to -185.9); chunks 4.3/6.2/8.4/11.1 deg, slam 27->28 now 38 deg (was 60), still carried by the freeze + bounce. Tip stays at or below pivot height (preview side frames 24-29). Rig rotation chunk between euler keys = angle of R(a)^T R(b) with R = Ry(-yaw) Rx(pitch) Rz(roll); a 10-line numpy check reproduces the 60 deg slam. Size pop at 56->57 and first-person lift path NOT changed (cannot be measured without a real-client shot; residual is the pivot/grip offset).
