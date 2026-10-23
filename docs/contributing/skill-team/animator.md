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
