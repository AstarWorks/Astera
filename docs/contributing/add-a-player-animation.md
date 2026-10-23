# Skin-rig animation YAML schema (contract shared by runtime + importer)

File: `content/animations/<id>.yaml`. One animation per file.

```yaml
id: sword-swing            # must equal file stem
length_ticks: 12           # total length, 20 ticks = 1s
loop: false
lock_movement: true        # runtime slows/locks walking while playing
blend_in_ticks: 2          # interpolate from rest pose
blend_out_ticks: 3         # interpolate back to rest pose, then restore the real player
bones:                     # fixed bone set (PoC): head, body, right_arm, left_arm, right_leg, left_leg
  right_arm:               # any bone may be omitted = stays at rest pose
    rotation:              # degrees, Euler X/Y/Z (Blockbench convention), relative to rest pose, pivot = vanilla joint (shoulder/hip/neck/waist)
      - { t: 0, v: [0, 0, 0] }
      - { t: 4, v: [-150, 0, 20], ease: ease_out }
      - { t: 12, v: [0, 0, 0] }
    position:              # pixels (1/16 block), relative to rest pose, in rig-local space (player body yaw)
      - { t: 0, v: [0, 0, 0] }
    scale:                 # optional, multiplier
      - { t: 0, v: [1, 1, 1] }
root:                      # optional whole-rig transform, same channel shape (rotation/position)
  position:
    - { t: 0, v: [0, 0, 0] }
```

- `ease` is the curve INTO that keyframe from the previous one: `linear` (default) | `step` | `ease_in` | `ease_out` | `ease_in_out` | `catmullrom`.
- Runtime samples curves per tick server-side and lets Display interpolation (interpolation_duration = 1) smooth between ticks.
- Keyframes sorted by `t`, `0 <= t <= length_ticks`.
- Extra bones (elbows/knees) are a later extension; the schema allows arbitrary bone names but the PoC rig only renders the six above (unknown bone = load error with clear message).

## Start in Blockbench

1. Open `content/animations/_rig/player-rig.bbmodel` as a native Blockbench
   project. It uses the **Generic Model** format, classic 4-pixel-wide arms,
   a 64×64 skin UV layout, six cubes and six sibling bone groups. No plugin
   or Bedrock animation export is required.
2. Save a working copy of the `.bbmodel`. Optionally import a copy of your
   own classic skin as a texture and apply it to all cubes for previewing.
   The template is intentionally untextured; the game uses each player's
   own skin, not the preview texture. Slim arms and skin outer layers are
   runtime concerns, not mesh exports from this importer.
3. Switch to Animate mode. Play the included `sword-swing` example, then
   replace it or add animations with distinct names such as `wave` or
   `heavy-swing`. Set animation length explicitly and set timeline snapping
   to **20 fps**. Add a rest key at time zero and a return-to-rest key at the
   end when appropriate. Set Loop to Once or Loop as desired.
4. Pose the groups using local Euler rotation, position and scale keyframes.
   Keep geometry, bone origins and the unanimated rest pose unchanged.
   Save the **project `.bbmodel`** with its animations; exporting a Bedrock
   `.animation.json` produces different axis conventions and is unsupported.

The `_rig/` directory contains authoring assets and is intentionally prefixed
with an underscore so the runtime animation loader skips it. The importer
writes only top-level `.yaml` animation files.

## Bone names and pivots

Group UUIDs connect Blockbench's `animators` to the groups. The importer uses
those groups' names, not the animator's cached display name. Both inline
outliner group definitions and separate `groups[]` definitions are readable.
Names are case-insensitive with spaces, underscores and hyphens removed for
matching: `RightArm`, `rightArm`, `right_arm` and `right-arm` all map to
`right_arm`. `torso` is also accepted for `body`.

| Canonical name | Vanilla pivot in Blockbench pixels | Classic cube bounds (from → to) |
| --- | --- | --- |
| `head` | `(0, 24, 0)` neck | `(-4, 24, -4)` → `(4, 32, 4)` |
| `body` | `(0, 24, 0)` | `(-4, 12, -2)` → `(4, 24, 2)` |
| `right_arm` | `(-5, 22, 0)` shoulder | `(-8, 12, -2)` → `(-4, 24, 2)` |
| `left_arm` | `(5, 22, 0)` shoulder | `(4, 12, -2)` → `(8, 24, 2)` |
| `right_leg` | `(-1.9, 12, 0)` hip | `(-4, 0, -2)` → `(0, 12, 2)` |
| `left_leg` | `(1.9, 12, 0)` hip | `(0, 0, -2)` → `(4, 12, 2)` |

Keep the six groups as siblings at the project root. Nested groups would
introduce inherited transforms absent from the six-bone contract and are
rejected. Unknown groups are listed in an error, even if they have no keys.
Duplicate aliases for the same bone, unknown animator UUIDs, rotated rest
poses, global rotations and quaternion interpolation also fail explicitly.
The importer does not export cubes and rejects non-vanilla group origins:
the runtime skeleton has fixed vanilla pivots. Optional contract `root` tracks can be authored in YAML;
a seventh Blockbench group named `root` is not supported by this six-bone importer.

## Export command

From the repository root:

```sh
make anim-import BB=content/animations/_rig/player-rig.bbmodel
# A working copy, optionally writing somewhere else:
make anim-import BB=/path/to/player.bbmodel OUT=build/animation-preview
# Equivalent Gradle task (paths resolve against the repository root):
./gradlew :tools:anim-import:importAnimations -Pbb=/path/to/player.bbmodel
# Or use the application CLI:
./gradlew :tools:anim-import:run --args='/path/to/player.bbmodel content/animations'
```

Every animation becomes one file. `SwordSwing` becomes `sword-swing.yaml`;
spaces, dots and other separators become hyphens, letters become lowercase,
and existing underscores/hyphens are retained. IDs always equal file stems.
Names that produce the same ID fail rather than overwrite each other. All
animations are validated before any file is written; an input error leaves
existing output files intact. Successful exports replace files with matching
IDs. Old files from renamed/deleted animations must be removed separately.
Filesystem failures during writing can leave a partial batch.

Exports set `lock_movement: true`, `blend_in_ticks: 2`, and
`blend_out_ticks: 3`. Edit these gameplay settings in YAML after importing;
reimporting resets them. `loop: loop` becomes `true`; `once` becomes `false`.
Blockbench Hold also becomes `false` with a warning: the runtime returns to
rest instead of holding the final pose indefinitely.

## Coordinate and timing conventions

The importer reads the **native Generic Model `.bbmodel` values** unchanged:

- Rotation is `[x, y, z]` in degrees relative to the rest pose. All three
  signs are preserved: a Blockbench right-arm X key of `-150` exports as
  `[-150, 0, 0]`. No degree-to-radian conversion or axis inversion occurs.
- Position is a rig-local pixel offset, with +Y up, +X toward the player's
  left and the template's front facing -Z. A Blockbench offset `[-2, 3, -4]`
  exports as `[-2, 3, -4]`. Do not divide by 16 or add the bone's pivot:
  the runtime handles pixels-to-blocks, fixed joints and body-yaw placement.
- Scale is a dimensionless `[x, y, z]` multiplier and is copied unchanged;
  missing components default to 1 (rotation/position components default to 0).
- Both animation length and key times use `round(seconds * 20)`, with
  nonnegative half-ticks rounding upward. Keys are sorted by source time.
  Negative/nonfinite times, keys beyond the declared length, lengths that
  round to zero, and two keys on one channel rounding to the same tick fail.
  Keep authored keys on the 0.05-second grid to avoid quantization surprises.

These signs follow Blockbench's native preview transforms, which add all
three rotation and position components directly. Bedrock-export axis flips
must **not** be applied again. See the upstream
[BoneAnimator preview and interpolation implementation](https://github.com/JannisX11/blockbench/blob/master/js/animations/timeline_animators.js)
and [Keyframe Bezier handles and numeric values](https://github.com/JannisX11/blockbench/blob/master/js/animations/keyframe.js).

## Interpolation and numeric values

Astera puts `ease` on the **destination** key. For each segment the importer
uses Blockbench's actual precedence:

1. A step on the preceding key becomes `ease: step` on the destination key.
2. Otherwise Catmull-Rom on either endpoint becomes `catmullrom`.
3. Otherwise Bezier on either endpoint is fitted to the nearest of `linear`,
   `ease_in`, `ease_out`, `ease_in_out`.
4. Otherwise the segment is `linear`.

The first key has no incoming ease. For example, interpolation values
`step, linear, linear` at ticks `0, 4, 12` export with `ease: step` at tick
4 and `ease: linear` at tick 12. A step on tick 4 does not freeze the segment
*into* tick 4.

Bezier fitting evaluates the source endpoint time/value handles at 19 evenly
spaced interior times, inverts the cubic time curve by bisection and minimizes
squared error across moving axes after normalizing each axis's endpoint delta.
The candidates use `t`, `t²`, `1-(1-t)²`, and symmetric piecewise quadratic
in/out respectively. Missing handles use Blockbench defaults (±0.1 seconds,
zero value offset). Each approximated segment emits a warning naming its
selected ease. Arbitrary overshoot, motion returning to an equal endpoint,
and different per-axis curves cannot be preserved by a single ease. Use
additional explicit keys when those details matter. Catmull-Rom preserves the
curve label; runtime tick sampling can still differ from Blockbench's preview.

Numbers, numeric strings (including signed decimals/exponents), and literal
Molang returns such as `return -90;` are accepted. Dynamic queries, variables,
function calls and arithmetic expressions are rejected with animation/bone/
channel context. Resolve expressions to numeric keys in Blockbench before
importing. Two-data-point pre/post discontinuities and effect channels
(sound/particles/timeline) are rejected rather than silently discarded.

## Verification

`tools/anim-import` follows the existing JVM authoring tools and uses the
shared Jackson and SnakeYAML dependencies. Its `AnimationImportIT` launches
the real CLI in a separate JVM against the shipped rig (plus edited working
copies), parses emitted YAML and covers signed coordinates, aliases, loops,
tick rounding, step/Catmull-Rom/Bezier, failures and preservation of existing
files when validation fails. It has no dependency on `plugin/`.

The manager/CI can run `./gradlew :tools:anim-import:test` or include it in the
normal multi-project `check` invocation. In-game validation additionally needs
the parallel skin-rig runtime: check the sample swing on a classic skin,
compare the direction of each axis, and confirm return-to-rest and loop behavior.
