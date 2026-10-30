# Brief: animator (sword swing animation) — S1 月輪の薙ぎ

Read `common.md` first. Role: the motion of the rig sword, nothing else. You do not choose colours, sprites or particles (vfx-artist), you do not model the sword (model-artist). Your swing is what makes the weapon look expensive or cheap.

## Goal
Two swings (hit 1 at skill tick 7, hit 2 at skill tick 31) plus the static REST holds between them, with: anticipation, a very fast main motion inside the first quarter of the effect, overshoot and settle, a 1-2 tick hold at the apex (the contact), the **cutting edge leading**, and the sword **pointing the right way in the hands**. The previous attempt: sword orientation slightly wrong, swing looked like a rotating plank, no weight.

## Look at
- Frames in `/tmp/claude-1000/-IdeaProjects-Astera/de20caa0-f14b-4c18-b441-07a23b79f448/scratchpad/pilot/frames/`: REST pose `a00_..._windup-start_sword-low-behind.jpg`, `f01_t42_recovery_f412.jpg`; the strike `b00_t06_hit1-ring-appears_f358.jpg` -> `b01_t07_hit1-contact_f359.jpg` -> `b03_t09_hit1-streak-ring_f362.jpg`; return `c00..c02`; hit 2 `e00..e07`; `f00_t40_finish-enemy-pushed_f409.jpg`.
- `case.json` beats rows 2-10 (hit 1) and 10-21 (hit 2). Important facts from them: in the video the blade is **not visible during the strike** (it is smeared into the gold ring in 1-2 frames), so the swing's shape is defined by the ring: radius about 2.7 block (= grip to tip), ~3.5H wide, tilted ~20 degrees with the right (caster's-right, away) end higher, and the white streak at waist/chest height pointing forward. The blade returns to REST at tick ~19-20 (hit 1) and ~40-41 (hit 2).
- Existing material to learn from, not copy: `content/vfx/bastion-sweep-high.yaml` and `bastion-sweep-low.yaml` (rig + swing keys), `content/vfx/demo-greatsword-swing.yaml`, `docs/contributing/vfx-art-direction.md` §7.

## Coordinates for the targets
Blade-tip direction is written as `(phi, pitch)`: `phi` = horizontal angle of the tip from forward, **0 = forward (+Z), +90 = toward the caster's left, 180 = backward, 270 = toward the caster's right**; `pitch` = degrees above horizontal (negative = tip down). Time `e` = effect tick (contact at **e = 4**). Grip (rig pivot) in anchor coordinates, caster feet at origin: `[-0.15, 1.0, 0.25]` for hit 1, `[-0.15, 0.85, 0.25]` for hit 2 (x negative = caster's right; grips can be moved +-0.1 if the hands look wrong in the 3rd-person view). The sword travels through the caster's right side first (phi 180 -> 270 -> 360=front -> 450 -> 540=back).

## Measurable targets
### Hit 1 (`bastion-s1-rig-high`, duration 20, played at skill tick 3)
| e | what | tip `(phi, pitch)` | roll / edge | notes |
|---|---|---|---|---|
| 0 | REST (spawned, identical to `hold_a`'s last pose, no pop) | (180, -20) | broad face sideways, edges up/down | |
| 1 | anticipation: tip pulled back/up against the swing direction | (160, -32) | roll starts turning | 1 tick, amplitude 15-25 degrees |
| 2 | sweep, tip at the caster's right-back | (250, -10) | edge leading (see below) | angular speed >= 80 degrees/tick |
| 3 | sweep, tip at the right-front | (320, 0 to +8) | edge leading | |
| **4** | **contact: tip forward**, the apex | **(360 +-20, +4 to +10)** | | hold 1-2 ticks (the skill's hit_stop 2 ticks freezes this window; the director passes `freeze: [{at: 4, ticks: 2}]`) |
| 5-7 | overshoot across the left side, decelerating (out_cubic) | (430 -> 490, -5 -> -12) | | ring completes behind the caster |
| 8-11 | follow-through then settle behind: overshoot 8-12 degrees past REST | (540+10, -28) -> (540, -20) | edge rolls back to REST | in_out_quad |
| 12-17 | slow settle to REST | (540 = 180, -20) | | arrives <= e17 (skill tick 20), static afterwards; the `hold_b` rig takes over from skill tick 21 |
Swing plane tilt: the sweep plane is tilted ~20 degrees (the end at the caster's right is higher): rig `tilt` roll about 20; check in the preview that the lowest point of the tip path is at the caster's LEFT-FRONT and that tip height stays within y 0.3..1.9.

### Hit 2 (`bastion-s1-rig-low`, duration 18, played at skill tick 27)
Same structure with: grip y 0.85; deeper anticipation (e1: pitch -40, phi 150); sweep plane tilt 20-25 degrees with the lowest tip point **near the floor at the caster's near-left side (tip y 0.2)**; apex at e4 held 2 ticks; a heavier feel (it is the bigger blow, importance 9/10): overshoot 15 degrees, settle by e14 (skill tick 41), static afterwards (`hold_c` from skill tick 42).

### Edge leading (measurable)
During e2..e7 the cutting edge normal (the rig-local +-X edge on the travel side) must be within 25 degrees of the tip's velocity direction, i.e. the blade must roll so its broad face is roughly horizontal (face normal up/down) while sweeping, and roll back to the sideways REST face before settling. Report the angle per tick from your computation.

### Speed and timing
- Main motion (e1 -> e4, anticipation to contact) takes <= 3 ticks = inside the first quarter of the effect's visible life. No linear easing on the main motion (`in_quad`/`in_cubic` into the contact, `out_cubic` out of it).
- Every rotation chunk <= 100 degrees/tick and never a 1-tick ease > 170 degrees (planner rejects it; art-direction §7).
- `respect_hit_stop: true` on the swing rigs.
- Display budget: swing rig parts (from `deliver/rig-parts.yaml`, <= 20) + trail displays: **rig parts + trail <= 44 displays alive at any tick** (cast-wide limit 60: sword 20 + trail 24 + the fx file 16 from vfx-artist). Trail: <= 24 alive (raise `step_deg` or drop `dark_rim` if over), `step_deg <= 30`, `life_ticks` 8-10, bands 2-3 + edge + dark_rim; geometry from the blade root (local y 0.7) to tip (local y 2.55); the colours/bands come from vfx-artist's `deliver/trail-bands.yaml` (until it exists, use neutral gold bands; the director pastes the final ones).
- Tip emitters: in each swing rig add `emitters:` entries `{id: tip_ember, particle_id: bastion-s1-tip-ember, point: [0, 2.55, 0], start_t: 2, end_t: 8}` and `{id: tip_glint, particle_id: bastion-s1-tip-glint, point: [0, 2.55, 0], start_t: 2, end_t: 7}` (particle files come from vfx-artist; if they do not exist yet the preview skips them).
- The REST pose of `bastion-s1-rig-hold` (3 static rigs, `follow: true`) must be pixel-identical to e0 of the swing rigs and to e17/e14 settle poses so no pop appears at the hand-over ticks (skill ticks 3, 21, 27, 42).

### In-hand correctness
- The grip box sits at the pivot; the hands position is the pivot: the grip is within 0.35 block of the caster's centre line and 0.85-1.15 above the feet, and the tip never passes through the caster's torso/head in the `top` and `eye` views at REST.
- At REST the tip points backward, 20 degrees down (compare `f01` frame: the blade crosses behind the caster's legs).

## Parts source
Parts come from `tools/skill-compare/data/bastion-greatsword-s1/deliver/rig-parts.yaml` (model-artist). Paste the list verbatim under `parts:` in all three of your files (do not edit its numbers; if something is wrong, put it in "requests"). Until it exists, use the 8-part list of `bastion-sweep-high.yaml` as a placeholder and re-paste when the file appears. `blade_face` is the only part id you may refer to.

## File territory
Create/edit only:
- `content/vfx/bastion-s1-rig-high.yaml` (id `bastion-s1-rig-high`, `duration_ticks: 20`, `orient: yaw`, `follow: false`, `rigs:` only)
- `content/vfx/bastion-s1-rig-low.yaml` (id `bastion-s1-rig-low`, `duration_ticks: 18`)
- `content/vfx/bastion-s1-rig-hold.yaml` (id `bastion-s1-rig-hold`, `duration_ticks: 48`, `follow: true`, three static rigs `hold_a` end_tick 3, `hold_b` start_tick 21 end_tick 27, `hold_c` start_tick 42 end_tick 47)
- `tools/skill-compare/data/bastion-greatsword-s1/deliver/animation-notes.md` (the per-tick tables, the pivot/tilt/rest numbers, any request to change skill timing)
- `tools/skill-compare/data/bastion-greatsword-s1/evidence/animator/**`
Do not touch the skill YAML, `elements:` (vfx-artist), or other weapons' VFX. Skill-timing changes (dash ticks, delays) are requests to the director.

## Self-check before handing back
1. `./gradlew :tools:vfx-preview:run --args='bastion-s1-rig-high --view side'`, then `top`, `eye`, and with `--freeze 4:2`; same for `-low`, and `bastion-s1-rig-hold`. Open the sheets. Produce a labelled strip of ticks e0..e10 for each view (a contact sheet is enough).
2. Compute the tip `(phi, pitch)` per tick from your keyframes (script in the scratchpad, not in `content/`) and tabulate against the targets above in `animation-notes.md`; report deviations.
3. Check the hand-over ticks (skill 3, 21, 27, 42) by loading hold + swing in sequence in preview (or by comparing poses numerically) and state that the poses match.
4. Real client (one at a time, shared slots): `make vfx-shot SKILL=bastion-greatsword-s1 ITEM=bastion-greatsword VIEW=side TICKS=60` only after the director has wired the ids in the skill (ask via "requests"); until then preview evidence is acceptable and you say real-client is unverified.

## Done means
- Rig/hold files load in `vfx-preview` with no planner rejection and respect the display budget.
- The per-tick table matches the targets (tip direction within +-15 degrees, contact tick exact, edge leading <= 25 degrees, no pop at hand-overs) or each miss is listed with the reason.
- Anticipation, overshoot, settle and the contact hold are visible in the labelled strip (you opened it and describe it).
- Handback lists what is still wrong. You do not write "passes".
