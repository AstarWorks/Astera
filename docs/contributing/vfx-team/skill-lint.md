# skill-lint

`make skill-lint SKILL=<skill-id> [PLAN=<plan.json>]` (tool: `tools/skill-lint/skill_lint.py`). A preflight the director runs before every capture; do not capture while it fails (exit 1). It checks four invariants and nothing else. It does NOT score the pose, blade angle or timing against any reference: that is judged on skill-compare. (The per-skill `targets.yaml` scoring and the regen stamp were removed 2026-10-27: they graded the content against numbers the same team wrote.)

| # | Check | Input |
|---|---|---|
| 1 | exactly one weapon visible: `visibleWeapons == 1` on every tick | plan dump |
| 2 | both hands on the grip: the right hand bone and the left arm's hand end (left_arm box end) each within a tolerance of `weapon.pos` on every tick | plan dump |
| 3 | feet on the floor: the lowest corner of both leg boxes at y = 0 (+-0.12); ticks where the dump's `caster` track says the feet are airborne are skipped and listed | plan dump |
| 4 | display budget: simultaneous Displays <= 60, particles spawned per tick <= 300 | skill + vfx YAML |

Statuses: `PASS`, `FAIL` (exit 1) and `NOT CHECKED`. NOT CHECKED means the input needed to verify is missing (for example the dump has no `left_arm` bone, or the plan is a Python simulation that has no presentation-lease model, so check 1 cannot be measured). It never counts as a pass and the summary line says so. A missing plan dump fails checks 1-3 loudly. Thresholds are constants at the top of the tool (not per skill).

## Plan dump

```json
{ "skill": "id", "ticks": [ { "tick": 0,
  "bones": { "<bone>": {"pos": [x,y,z], "rot": [qx,qy,qz,qw]} },
  "weapon": {"pos": [x,y,z], "rot": [qx,qy,qz,qw], "tip": [x,y,z], "edgeNormal": [x,y,z]},
  "visibleWeapons": 1 } ] }
```

Frame: caster-local, +x right, +y up, +z forward (toward the target), origin at the caster's feet. `tick` is the skill tick (= reference tick). Conventions the lint relies on: `weapon.pos` is the grip origin, the blade axis is `tip - pos`, `edgeNormal` is the direction the cutting edge points (local X), so the broad-face normal is `normalize(axis x edgeNormal)`. Bone `rot` rotates local vectors (forward +z, up +y) into caster-local space.

Produced by `make skill-lint-plan SKILL=<skill-id>` (ADR-0276: a headless cast of the real skill; `visibleWeapons` is the worst viewer of the presentation-lease model, bone/weapon numbers are the planner's math). Bones emitted: `head`, `body`, `right_arm`, `left_arm`, `right_leg`, `left_leg` (box centres), plus `hips` (hip pivot, root rotation) and `right_hand` (grip point of the right arm). A skill without a performance rig (`weapon: main_hand`) has no dump.

## Plan dump extensions (skill-preview)

Additive, ignored by the lint: `slim`, `weaponScale`, `bones.<bone>.scale` (box size multiplier, `size_in_blocks = scale * 0.5`), `caster` (`[{tick, pos}]`, the caster's feet per skill tick relative to the cast position, so dashes are in it) and `displays` (the fx Display entities as `astera-sim-trace/1` display events with an added `tick` = skill tick; the performers are not in it; positions relative to the caster's feet at the cast, in the sim frame: `+z` forward, `+x` the caster's LEFT). Used by [skill-preview](skill-preview.md).


## Keypoints, pose-solve and overlays (not part of the lint)

`tools/pose-solve/` (`make pose-solve SKILL=<skill>`: reference keypoints -> body and wrist keys in `content/skill-lint/solved/`) and `tools/pose-solve/overlay.py` (plan dump projected onto the annotated reference frames, PNG per beat for skill-compare) still exist as authoring aids. They no longer write or read targets and the lint does not run them.
