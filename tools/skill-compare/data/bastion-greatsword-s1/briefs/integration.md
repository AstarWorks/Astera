# Integration plan (director) and acceptance rows (for vfx-reviewer)

## Order of work
1. **Parallel round 1**: model-artist (model + `rig-parts.yaml`), animator (rigs with placeholder parts, re-pasted when `rig-parts.yaml` lands), vfx-artist part 1 (textures), sound-designer (all cues). No shared files.
2. **Round 2**: vfx-artist part 2 (fx files, particles, trail bands, cues) once the textures exist; animator re-pastes parts and trail bands; director integrates.
3. **Round 3**: independent vfx-reviewer on the site; findings go back to the owning specialist as a row-addressed fix list; repeat until all rows pass with evidence. The reviewer never edits content.

## What the director assembles (no edits inside specialist content files except the two named below)
`content/skills/bastion-greatsword-s1.yaml` (director-owned), rewritten as:
| skill tick | step | source |
|---|---|---|
| 0 | `hide_held_item` 46 ticks (unchanged), `play_vfx bastion-s1-rig-hold` (delay 0) | animator |
| 0-3 / 24-26 | `dash_strike` (unchanged: 1.6 / 0.7 block) | |
| 0 / 24 / 3 / 27 etc. | `play_sound` steps from `deliver/sound-cues.yaml` (`delay_ticks` = tick), vanilla optional layers | sound |
| 3 | `play_vfx bastion-s1-rig-high` and `play_vfx bastion-s1-fx-high`, each with `freeze: [{at: 4, ticks: 2}]` | animator, vfx |
| 5-8 | `spawn_particle` steps for the vanilla particle ids at the cue ticks | `deliver/particle-cues.yaml` |
| 7 | damage cone / hit_stop 2 / charge status (unchanged numbers 7, 3.6 block, 110 deg) | |
| 27 | `play_vfx bastion-s1-rig-low`, `play_vfx bastion-s1-fx-low`, `freeze: [{at: 4, ticks: 2}]` | |
| 29-32 | vanilla particle steps for hit 2 | |
| 31 | damage 9 / hit_stop 2 / knockback 0.5 (unchanged) | |
The old `play_vfx bastion-sweep-high/low` and old vanilla `play_sound` ids are removed from S1 only; the old files stay for S2/S3/EGO.
Integration edit 1: paste `deliver/trail-bands.yaml` bands into the `trails:` of `bastion-s1-rig-high/low` (the animator's geometry stays). Integration edit 2: nothing else in the content files. If a defect lives in a specialist file, it goes back to that specialist.

## Capture plan (real client, one capture at a time; slots are shared)
```
SKILL=bastion-greatsword-s1 ITEM=bastion-greatsword VIEW=compare CASE=bastion-greatsword-s1 bash tools/vfx-shot/vfx-shot.sh
make vfx-shot SKILL=bastion-greatsword-s1 ITEM=bastion-greatsword VIEW=caster   TICKS=60
make vfx-shot SKILL=bastion-greatsword-s1 ITEM=bastion-greatsword VIEW=opponent TICKS=60
make vfx-shot SKILL=bastion-greatsword-s1 ITEM=bastion-greatsword VIEW=side     TICKS=60
make skill-pairs CASE=bastion-greatsword-s1 AT="11.633:rest 11.933:ring 11.967:hit1 12.067:streak 12.633:rest2 13.133:ink 13.2:hit2 13.4:ring2 13.7:recovery"
```
Requirements: NoAI target at 2.4 / 1.7 block; the normal-input run (left click with the real weapon, resource cost and cooldown) separately from the `--force` compare capture; `captureQuality` checked; recapture after any content change (content revision must match). Record commit, command, pack SHA, time in `case.json` notes and `evidence/integration/`.

## Acceptance rows (the reviewer judges each on the compare site, frame by frame at native fps, and writes pass/fail + evidence: frame/tick numbers and an image path; unopened images do not count; any fail = not accepted)
| # | Row | Measure / target | Compare against |
|---|---|---|---|
| M1 | Sword silhouette | long slab ~9:1, tapered tip, stepped guard wider than blade, lime inlay near guard | `f01_t42_recovery_f412` |
| M2 | Materials | >= 5 distinct textured materials on the model; no flat single-colour blade face; edge highlight + dark rim visible in the real client frame | model board + client frame |
| M3 | Model in hand | tip not through the body, both hands, 1st/3rd person; the swung rig sword is the same sword | context boards, caster/side views |
| A1 | REST pose | tick 0-5, 20-27, 41+: tip backward, ~20 deg down, broad face sideways, guard at the hip | `a00`, `c02`, `f02` |
| A2 | Anticipation + speed | tick 3-4 pull-back visible; main motion <= 3 ticks from tick 4 to contact; no linear easing look | tick strip |
| A3 | Contact apex | blade tip forward at tick 7 and tick 31; held 1-2 ticks (hit stop) | `b01`, `e02` |
| A4 | Overshoot + settle | overshoot past rest, settle by tick 20 / 41, no pop at hand-over ticks 3, 21, 27, 42 | frame-to-frame |
| A5 | Edge leading | cutting edge leads during tick 5-11 / 29-35 (broad face roughly horizontal in the side view) | side view |
| T1 | Crescent/ring | needle tips, white-hot inner edge, dark outer rim, gold body; ring ~3.5H wide, right end higher by ~20 deg; hit 2 lower and thicker | `b03`, `e05` |
| T2 | Ink flash (hit 2) | flat saturated yellow, ragged edge, lasts 2-3 ticks around tick 30-31, does not hide the target for longer | `e00`-`e02` |
| T3 | Streak + sparks | white-hot streak at chest height, ~2.5-3H long, orange spark streaks | `b03`, `e03` |
| P1 | Particle mix | both display-particles and vanilla particles visible; staggered start ticks; dark specks/smoke mixed with bright | frames tick 7-12, 31-37 |
| P2 | Layer order | flash at the contact tick, smoke after the flash, ring/streak fade over 8-10 ticks | tick strip |
| P3 | Readability | effect visible in the caster view and the opponent (3rd-person) view; does not occlude the target beyond 3 ticks | caster/opponent |
| S1 | Sound timing | timeline waveform: whoosh peak 1 tick before contact, impact on tick 7/31 (+-1), hit 2 louder; cues present in the pack's `sounds.json` | timeline PNG, `sounds.json` |
| S2 | Sound character | (human ear, if available) heavy steel, armour/metal ring, ground slam on hit 2; otherwise "unverified" | founder |
| X1 | Timing vs video | every VFX/contact beat within +-1 tick of the beat sheet (tick numbers in `case.json` beats) | beats + pairs |
| X2 | Budget | <= 60 displays alive per cast (counted), vanilla particles <= 300/tick | counting script |
| X3 | Non-regression | `bastion-sweep-high/low`, `bastion-arc/ink/smoke`, `bastion-s3-*` unchanged (git diff empty); S2 still loads | `git diff` |
| X4 | Real input | left-click with the real weapon triggers the skill; refusal on cooldown/resource | separate recording |
Verdict vocabulary follows `docs/contributing/limbus-skill-reproduction.md` §7 (検証不合格 until every row passes with evidence). The reviewer lists residuals per row and which specialist owns each.
