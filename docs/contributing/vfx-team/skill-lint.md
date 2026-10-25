# skill-lint

`make skill-lint SKILL=<skill-id> [CAPTURE=<dir>]` (tool: `tools/skill-lint/skill_lint.py`). The director runs it before every capture and does not capture until it passes (exit 0). It exists so reviews judge taste, not facts a machine can read (two swords, wrong pose, fx leading the blade, silent beats).

| # | Check | Input |
|---|---|---|
| 1 | per-beat pose vs the reference targets | `build/skill-lint/<skill>/plan.json` + `content/skill-lint/<skill>.targets.yaml` |
| 1b | plan dump projected onto the annotated reference frames: skeleton + blade line pixel error per beat, plus an overlay PNG per beat | `plan.json` + `content/skill-lint/<skill>.keypoints.yaml` (skipped when the skill has no keypoints file) |
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

Produced by `make skill-lint-plan SKILL=<skill-id>` (ADR-0254: a headless cast of the real skill; `visibleWeapons` is the worst viewer of the presentation-lease model, bone/weapon numbers are the planner's math). Bones emitted: `head`, `body`, `right_arm`, `left_arm`, `right_leg`, `left_leg` (box centres), plus `hips` (hip pivot, root rotation) and `right_hand` (grip point of the right arm). A skill without a performance rig (`weapon: main_hand`) has no dump.

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
| `body.lean_deg` | torso pitch forward, degrees, measured in the torso's OWN heading frame (a forward bend is positive however far she has turned; before 2026-10-26 it was measured along the caster's +z and read negative past 90 degrees of twist) |
| `body.twist_deg` | absolute torso yaw from the caster facing, degrees |
| `fx: none` | no particle emission and no display element may start in these ticks (hold / coil) |
| `sound.window: [a, b]` | a `play_sound` must fire in these ticks |
| `regen` | `script`, `inputs` (files + skill step types), `outputs` (globs) for check 6 |

Rules: targets describe the reference, never the current content. Do not retune a target to make a check pass: fix the content, or widen the tolerance and say why in `note`. Camera-only beats (cuts, ink fills) carry no pose target, only `sound`/`fx`. Add a targets file when a skill gets a reference breakdown; without one the lint fails.

## Keypoints and the overlay check (check 1b)

Poses are read off the reference as pixels, not degrees. Per held beat of a skill (about 8-9) an agent proposes ~16 keypoints on the reference frame and a human corrects them; a deterministic solver turns them into the body keys, the wrist keys and the targets; check 1b projects the runtime plan dump back onto the same frame.

```
content/skill-lint/<skill>.keypoints.yaml          annotated data (committed): head_top neck r/l_shoulder r/l_elbow r/l_wrist pelvis r/l_knee r/l_ankle grip pommel blade_tip
   | make skill-compare  ->  /annotate.html?skill=<skill>     drag the points; confidence, depth near|mid|far, blade depth/face/floor, facing
   v
make pose-solve SKILL=<skill>                      tools/pose-solve/solve.py -> content/skill-lint/solved/{<body>.yaml,<rig>.yaml,<skill>.targets.yaml}
   v   (the manager copies them over the shipped keys / targets when the review says so; the tool never overwrites shipped content)
make skill-lint-plan SKILL=<skill> && make skill-lint SKILL=<skill>      check 1b -> build/skill-lint/<skill>/overlay/<beat>.png
```

- Annotation: pixel `xy` in the 1280x720 reference frame (`t = start_s + 0.05 * tick`, frames are extracted from the cached video by `tools/pose-solve/kp.py frame`). `c` = confidence 0..1 (0 = hidden: the solver uses priors). `depth` = near | mid | far relative to the pelvis (resolves what a 2D point cannot). `r_`/`l_` are the character's right / left. Per beat: `tick` (annotated frame), `key_tick` (skill tick at which the pose becomes visible = the rig key), `ticks` (hold range for the targets), `facing` (0 = torso faces the camera, 90 = screen right, 180 = away), `confidence` (becomes the target tolerances), `camera` (overrides on top of the case's compare camera: `bearing` 0 front / 90 right side / 180 behind, `elevation`, `distance`, `fov`, `target`; default = the `intent` camera key active at `tick`, so a beat shot from another angle - hard cuts - needs its own), `blade: {depth: toward|away|inplane, face: up|camera|edge|unknown, floor}`. `view` is written by the solver: an image-space pan/zoom fit of the camera estimate (the reference camera is a hand estimate).
- Solver: unknowns per beat = root (lean, yaw, roll, height), head, both arms, both legs, view; residuals = 2D reprojection of the joints (x confidence), depth-order hinges, feet on the floor, left hand on the grip, weak priors. The blade is solved analytically (tip pixel + `blade_length_ref` -> direction, `depth`/`floor` pick the sign, `face` fixes the roll); the wrist key is the rotation from the arm frame to that blade frame (euler `Ry(-a) Rx(b) Rz(c)`, ADR-0254). `tools/pose-solve/rigmodel.py` is a numpy port of the runtime math and matches a Kotlin plan dump to 1e-6 (`overlay.py --simulate` builds a plan from content without a JVM).
- Check 1b: `rms_frac` = RMS pixel distance of the core joints / the figure's height in the image; `blade_deg` = on-screen angle between the annotated and the projected grip-to-tip line; `tip_off_frac` = distance of the annotated tip from the projected blade LINE (the rig blade is 1.34x the reference blade, so the tip is judged across the line, not along it). Limits: `overlay_tolerance` in the keypoints file (default 0.12 / 14 deg / 0.06). A quick A/B of two pose sets: `python3 tools/pose-solve/overlay.py <skill> --simulate --refit-view --body A.yaml --rig A-rig.yaml`.
- Caveat: a solver output fitted to the annotation will of course pass 1b against that annotation. 1b is a regression guard on the runtime (does the shipped plan still match the reference pixels) and a way to see where a hand-authored pose is far from the reference; the independent opinion remains check 1's targets and the review.
