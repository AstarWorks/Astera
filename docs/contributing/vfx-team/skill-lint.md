# skill-lint

`make skill-lint SKILL=<skill-id> [CASE=<case-id>] [PLAN=<plan.json>]` (tool: `tools/skill-lint/skill_lint.py`). A preflight the author runs before every capture; do not capture while it fails (exit 1). It checks the invariants below and nothing else. It does NOT score the pose, blade angle or timing against any reference: that is judged on skill-compare, and whether the build has the reference's objects is judged by the inventory diff ([beat-sheet.md](beat-sheet.md) "Inventory"; the lint only checks that the inventory's models exist and are shown). `CASE` defaults to the skill id; it locates `tools/skill-compare/data/<case>/inventory.md` (checks 1 and 6) and the case intent (check 7). (The per-skill `targets.yaml` scoring and the regen stamp were removed 2026-10-27: they graded the content against numbers the same team wrote.)

| # | Check | Input |
|---|---|---|
| 1 | held weapons: `visibleWeapons` on every tick equals the number of **held** rows of the inventory (anchor "held" / "hand"; 1 when the case has no inventory yet). A thrown or split blade is its own inventory row with its own construction, never a second held weapon. An unreadable held count (no `[O]`) fails: ask the founder | plan dump + inventory.md |
| 2 | grip, from the item data: the right hand bone within a tolerance of `weapon.pos` on every tick; the left arm's hand end too **only for a two-handed weapon** (item `attributes.attack_speed <= 1.0`, e.g. bastion 0.8, verdict-hammer 0.8). A one-handed weapon (attack_speed > 1.0, e.g. couture 3.0) may hold the left hand free (PASS with that note). Without the item or its attack speed the left-hand line is NOT CHECKED | plan dump + `content/items/<item>.yaml` |
| 3 | feet on the floor: the lowest corner of both leg boxes at y = 0 (+-0.12); ticks where the dump's `caster` track says the feet are airborne are skipped and listed | plan dump |
| 4 | display budget: simultaneous Displays <= 60, particles spawned per tick <= 300 | skill + vfx YAML |
| 5 | hits are events: stuck / linked objects and hit effects spawn from `on_hit:` / `hit_entity:` / target anchors, never from a `delay_ticks` with a cone targeter tuned to the capture dummy | skill + vfx YAML |
| 6 | inventory models: every 3D-object row of the inventory names a model id (`astera:<id>` or `` `<id>` `` in 構成案) that exists under `content/models/items/`, and that model is shown by one of the skill's vfx (`item_model:` in a display element, `visual.vfx` of a projectile sub-skill, or the held item); a model the skill shows that no inventory row lists also fails. NOT CHECKED without an inventory (the shown models are listed so the author can write them into the inventory) | inventory.md + skill + vfx YAML + models |
| 7 | no capture-distance bake: no caster-anchored vfx places a 3-vector (`translate` / `offset` / `position` / `pos` / `anchor_offset`) whose x or z component is within +-0.2 of the case intent enemy distance (`intent.actors[enemy].keys[0].pos`). Vfx played only from target-anchored targeters (`target`, `hit_location`, `projectile_position`, `cone`, `nearby`, `nearest`) are skipped. NOT CHECKED without an intent or below 1.5 blocks | skill + vfx YAML + case intent |
| 8 | windup length: a skill-level `windup: {ticks}` is at most 20 ticks unless the skill's top-level `importance:` is 9 or more (ADR-0290; vfx-art-direction.md telegraph rules). PASS when the skill has no windup | skill YAML |

Statuses: `PASS`, `FAIL` (exit 1) and `NOT CHECKED`. NOT CHECKED means the input needed to verify is missing (for example the dump has no `left_arm` bone, the plan is a Python simulation that has no presentation-lease model so check 1 cannot be measured, or the case has no inventory yet). It never counts as a pass and the summary line says so. A missing plan dump fails checks 1-3 loudly. Thresholds are constants at the top of the tool (not per skill).

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

## Structure stills (`make skill-3d`)

`make skill-3d SKILL=<id> [CASE=<id>] [SHOTS=<dir> VIEWS=orbit,top TICKS=...]` renders headless PNGs of the skill's Display entities (ItemDisplay models, BlockDisplay solids, anchored lines) from an orbit and from above. It is the primary review surface of the **structure commit** (workflow v4 step 4a): one object per inventory row, zero sprites, zero particles. The orbit and top stills go beside the round note (`skill-3d:` line of `## Inventory`, checked by `make round-gate`).

## Keypoints, pose-solve and overlays (not part of the lint)

`tools/pose-solve/` (`make pose-solve SKILL=<skill>`: reference keypoints -> body and wrist keys in `content/skill-lint/solved/`) and `tools/pose-solve/overlay.py` (plan dump projected onto the annotated reference frames, PNG per beat for skill-compare) still exist as authoring aids. They no longer write or read targets and the lint does not run them.
