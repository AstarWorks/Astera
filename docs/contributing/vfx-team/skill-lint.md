# skill-lint

`make skill-lint SKILL=<skill-id> [CAPTURE=<dir>]` (tool: `tools/skill-lint/skill_lint.py`). The director runs it before every capture and does not capture until it passes (exit 0). It exists so reviews judge taste, not facts a machine can read (two swords, wrong pose, fx leading the blade, silent beats).

| # | Check | Input |
|---|---|---|
| 1 | per-beat pose vs the reference targets | `build/skill-lint/<skill>/plan.json` + `content/skill-lint/<skill>.targets.yaml` |
| 2 | `visibleWeapons == 1` every tick, weapon grip on the right-hand bone | plan dump |
| 3 | particle / display fx do not lead the blade and do not start in `fx: none` beats | skill + vfx YAML, plan dump (falls back to `rig_path.py` when there is none) |
| 4 | simultaneous Displays <= 60, particles per tick <= 300 (director.md budget), haze sprites | vfx YAML, sprite PNGs |
| 5 | every beat with `sound.window` has a `play_sound` in it; the `.ogg` exists | skill YAML, `content/sounds/` |
| 6 | regen outputs match their inputs | `content/skill-lint/<skill>.regen.json` stamp (written by the regen script via `--stamp-regen`) |
| 7 | with `CAPTURE=<dir>`: `meta.json` valid, `contentRevision` equals the current tree, repeated-frame run. The per-frame weapon-count heuristic for FPS=60 captures is not implemented yet (the tool prints a `[TODO]` line, not a PASS) | capture dir |

A missing plan dump fails checks 1 and 2 loudly. The runtime writes the dump (contract below); the lint never passes without it.

## Plan dump

```json
{ "skill": "id", "ticks": [ { "tick": 0,
  "bones": { "<bone>": {"pos": [x,y,z], "rot": [qx,qy,qz,qw]} },
  "weapon": {"pos": [x,y,z], "rot": [qx,qy,qz,qw], "tip": [x,y,z], "edgeNormal": [x,y,z]},
  "visibleWeapons": 1 } ] }
```

Frame: caster-local, +x right, +y up, +z forward (toward the target), origin at the caster's feet. `tick` is the skill tick (= reference tick). Conventions the lint relies on: `weapon.pos` is the grip origin, the blade axis is `tip - pos`, `edgeNormal` is the direction the cutting edge points (local X), so the broad-face normal is `normalize(axis x edgeNormal)`. Bone `rot` rotates local vectors (forward +z, up +y) into caster-local space.

## Targets file (`content/skill-lint/<skill>.targets.yaml`)

Header: `skill`, `caster_height` (H), `capture_offset_ticks` (capture frame = tick + 6 for the compare camera), `bones` (`hips`, `torso`, `hand` bone names in the dump), `grip_tolerance`, `budget`. Every pose value is `[target, tolerance]`.

Per `beats[]` entry:

| Field | Meaning |
|---|---|
| `id`, `ticks: [first, last]`, `confidence`, `note` | beat name, inclusive tick range, how sure the reading of the reference is, why |
| `blade.tip_side_deg` | angle of grip->tip in the (z, y) side plane: 0 toward the target, +90 up, +-180 straight behind, negative = below horizontal |
| `blade.tip_y` | tip height in blocks above the feet |
| `blade.face_normal`, `blade.face_tol` | broad-face normal (sign-agnostic) and angular tolerance in degrees; omit when the roll cannot be read |
| `body.hips_h` | hips bone height / H |
| `body.lean_deg` | torso pitch forward, degrees |
| `body.twist_deg` | absolute torso yaw from the caster facing, degrees |
| `fx: none` | no particle emission and no display element may start in these ticks (hold / coil) |
| `sound.window: [a, b]` | a `play_sound` must fire in these ticks |
| `regen` | `script`, `inputs` (files + skill step types), `outputs` (globs) for check 6 |

Rules: targets describe the reference, never the current content. Do not retune a target to make a check pass: fix the content, or widen the tolerance and say why in `note`. Camera-only beats (cuts, ink fills) carry no pose target, only `sound`/`fx`. Add a targets file when a skill gets a reference breakdown; without one the lint fails.
