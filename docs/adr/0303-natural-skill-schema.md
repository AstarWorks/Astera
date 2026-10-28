---
status: Proposed
date: 2026-10-28
deciders: founder, Claude
supersedes: null
partially_supersedes: 0301
superseded_by: null
---

# ADR-0303: Natural skill schema: anchors, thin vanilla objects, motion as rates

## Status

Proposed. Board SKL-16. Design only; no engine code changes in this task.

**Partially supersedes [[adr/0301-skill-engine-v2-frame-tree]]** (0301's body is not edited). Map:

| 0301 item | Fate |
|---|---|
| Decision 1, frame tree, `reparent(keepWorld)`, named points, victim frames, model points in `content/frames` | KEPT. The tree nodes are the anchors of this ADR. SUPERSEDED only: the positional `[left, up, forward]` offset convention (now named-axis maps, section 1). |
| Decision 2, motion laws | SPLIT. Gameplay laws (fly, path, stick, reel, gameplay `pose`/`spin`, the translate-before-rotate order) KEPT. Everything that moves a DISPLAY (`pose` as a display rotation language, `track`, Euler triples) SUPERSEDED by presentation motion (section 3). |
| Decision 3, hits and contacts | KEPT. |
| Decision 4, two sections, two sealed families | KEPT (a presentation law still cannot move a gameplay frame). The presentation op list (`mesh`, `plate`, `line`, `trail`, `emit`, `track`) is SUPERSEDED by `display`, `particle`, `ribbon`, laws. |
| Decisions 5-11 (CEL, handlers, composition, modifiers, reload, old engine deleted, layout) | KEPT. |

## Context

The audit of v2 against v1 (`/tmp/claude-1000/skill-engine/audit/root-causes.md`, RC1-RC8) shows v2 put layers between the YAML and
the two things the client really has: a Display entity (location, yaw/pitch, `Transformation`, interpolation fields) and
`spawnParticle(count, offset, extra, data)`. The layers (Euler triples composed in a hidden order, x5 glyph scale, frame-distance-dependent
plate size, a 0.3 random-sphere particle model, per-tick sampled rotation, world-teleported "attached" weapons) produce the founder's
rejections: wrong rotation, late rotation, floating weapon, particles that are not the v1 particles. The converter then made it worse:
`coin1.yaml` is 621 lines of per-tick keyframe dumps, and e.g. petal roll `309 -> 349 -> 509` is only "spin 40 degrees per tick".

Founder direction 2026-10-28: motion is animation (say the change per tick and its direction, not the value at every tick); the schema
must be natural; particle count and speed are plain vanilla fields; VFX and 3D models are just Display entities (quaternion, basis axes,
origin); no more superficial patches.

## Decision

Principle: **an author-visible field is either a vanilla argument 1:1, or a rate/law over named axes. Everything else is a load-time macro
that EXPANDS into those and is printed by the lint/preview output (`astera skill expand <id>`).** No constant hides in code.

### 0. Conventions, stated once (RC7)

- World is Minecraft's: +X east, +Y up, +Z south. Right-handed.
- Every anchor basis is the named triple **(forward, left, up)** with forward x left = up (right-handed; at yaw 0 forward = +Z, left = +X).
- A rotation is "`deg` about a named axis", positive = **right-hand rule** (counter-clockwise looking from the axis tip toward the origin).
  Consequences worth memorizing: +deg about `left` tips `up` toward `forward` (overhand chop: tip goes up, forward, down);
  +deg about `up` turns `forward` toward `left` (left turn; the vanilla entity `yaw` field is the opposite sign, `yaw = -deg about up`, and is
  only ever written by the engine); +deg about `forward` takes `left` toward `up` (leans to the right).
- Offsets and vectors are written as **name-keyed maps** `{forward: 1.2, up: -0.2}` (missing = 0), never positional triples, so the order
  can never be confused. Where a raw vector is a vanilla argument (particle `offset`, display `translation`) it is `[x, y, z]` in WORLD axes
  (particle) or entity-local axes (translation), and CEL values such as `= anchor.forward` produce world vectors.
- Rotations are never Euler triples. A rotation is `{axis, deg}`, a list of them (applied in order, see 3.2), or a quaternion `[x, y, z, w]`.
- Interpolating rotation is always slerp (shortest path; the client does the same). A segment that would turn more than 170 degrees is
  split by the engine, never left to the client's shortest-path choice.
- One unit: blocks. Time: ticks.

### 1. Anchors

An anchor = **origin + named axes** (forward, left, up; items add `long`, `edge`, `face = long x edge`). Anchors are the frame-tree nodes of 0301.

```yaml
anchors:
  - {id: hand,   anchor: caster.hand_r}                                  # follows the caster every send
  - {id: muzzle, anchor: caster.eye, offset: {forward: 0.5}, mode: snapshot}   # copied once at activation, then world-fixed
  - {id: blade,  anchor: shears.blade_a}                                 # part anchor; axes long/edge/face from content/frames
  - {id: flash,  anchor: contact, turn: [{axis: up, deg: 90}]}           # at a contact point; created when the event fires
```

| field | meaning |
|---|---|
| `anchor` | parent anchor: `world`, `caster` (feet, yaw-only basis), `caster.eye` (full look basis, no roll), `caster.hand_r`/`hand_l`, `<item-frame>.<part>` (axes from `content/frames/<item>.yaml`), `contact`, `victim.<root/body/head>`, or any declared anchor. A gameplay frame is a valid parent (read-only). |
| `offset` | name-keyed map in the PARENT's axes. |
| `turn` | list of `{axis, deg}` relative to the parent basis, applied in list order. |
| `mode` | `follow` (default): origin and axes re-read from the parent at every send. `snapshot`: pose copied once at activation, parent becomes `world`. |
| `mount` | `caster`: only on `follow` anchors whose chain is rooted at the caster (RC2). Objects on it become real passengers of the caster entity; their pose is `translation + left_rotation` relative to the caster, the client moves them with the player. Without `mount` a rooted object is world-teleported with `teleport_duration` = segment length. The engine re-sends a mounted object only on a segment boundary or when the caster heading drifts more than 3 degrees from the last send (never per tick). The passenger attachment height is ONE calibrated engine constant, printed by `expand`; v1 `VfxPlayer` (d41b82f1e, ADR-0276) is the reference behaviour. |

Gameplay frames stay in `frames:` (hit volumes need them). A gameplay frame moved only by closed-form laws (`fly` without drag/gravity,
`spin`, constant-rate `pose`) EXPORTS those law segments to the displays anchored on it, so a presentation on a spinning gameplay frame (coin 2
shears) is tweened by the client exactly like a presentation law. A frame moved by a reactive law (`follow`, `reel`, collision) is sampled
at its step (section 4).

### 2. Objects: thin vanilla mappings

#### 2.0 One timing block for every kind

Every op that does something over time carries the SAME timing block: `display`, `ribbon`, `particle`, `particle_def`, `sound`, and the
gameplay ops (`hit`, `damage`, `status`, `impulse`, `knockback`, `projectile`). A repeating sound or emit is this block, never a separate `every` op.

| field | meaning |
|---|---|
| `at` | start: a tick (since cast start, or since the event inside a handler) or an event, with optional offset: `at: 8`, `at: release`, `at: "release+2"`. Inherited from the enclosing timeline group, overridable per op. |
| `for` | duration in ticks of the op's window; `for: cast` = until the cast ends. **Extended kinds** (display, ribbon) without `every`: the instance lives `for` ticks. **Point kinds** (particle, sound, hit, damage) with `every`: the window in which they repeat. |
| `every` | repeat period in ticks: occurrences at `at + k * every` (exact multiples, k = 0, 1, ...) inside the window. Absent = one occurrence. May be a range with an `ease` (3.7.1): `every: {from, to, ease}`, fire ticks computed statically. |
| `times` | maximum number of occurrences (with `every`). |
| `until` | an event; ends the window (and every live instance of an extended kind) the first time it fires. |
| `lasts` | extended kinds WITH `every` only: how long each occurrence lives. Required there (no hidden default), so repeats cannot pile up. |

- Repeat index `i` (0-based) and occurrence count `n` (`times`, else `floor(for / every)`, else -1 for `until`-bounded) are CEL variables (3.5).
- A repeat with no bound (`every` without one of `for`, `times`, `until`) is a load error.
- A timeline group `{at, every, for, times, until, do: [...]}` is only shorthand: its block is the default of every op in `do`, and an op's own field
  overrides it field by field.
- Examples of the same grammar on different kinds: sound `{at: 4, every: 4, until: release}`; particle `{at: 7, every: 1, for: 7}`; display
  `{at: 7, for: 5}`; trail ribbon `{at: 4, until: release}`; multi-hit `{op: hit, at: 8, every: 2, times: 3}`; damage over time
  `{op: damage, at: 0, every: 10, for: 60}`. Each occurrence of a gameplay op sees its own `i`.

#### 2.0b No hidden defaults

Every field is either **required** or defaults to the **identity value** (0, 1, empty, absent, or the plain vanilla default), and that default is
written in the field table. A value that differed in v1 is written EXPLICITLY by the converter, never supplied by the engine.

| field | choice | why |
|---|---|---|
| `fade.exponent` / ribbon `fade` (v1 `fade_power`) | identity default **1.0** (linear); the converter writes `exponent: 1.5` on every v1 element, because v1 defaulted to 1.5 (v2 silently changed it to 1.0, unapproved) | the schema default is the neutral value, and the v1 look is visible in the content instead of in the engine |
| `count`, `offset`, `extra` (particle) | identity 1, [0,0,0], 0 | vanilla overload arguments, written down |
| `scale`, `size` growth, `volume`, `pitch` | identity 1 (volume/pitch: vanilla sound defaults) | |
| `billboard`, `context`, `view_range`, `yaw`, `pitch` | vanilla `fixed`, `fixed` (decision), 1.0, 0, 0 | |
| `ribbon.style` (`soft | crisp | both`), `ribbon.width_frac`, `projectile.block.mode` | **required** (v2 hid `soft`) | no neutral value exists |
| `lasts`, `for`/`until`/`times` bounds, `block.restitution`, `block.times`, `block.for` | **required** where they apply | no neutral value exists |

#### 2.1 `display` (kinds `item`, `block`, `text`, `sprite`)

Every field is a vanilla field. Defaults are vanilla defaults.

```yaml
- id: shears
  op: display
  kind: item
  anchor: hand                         # anchor; the entity LOCATION is the anchor's world origin (+ offset)
  offset: {forward: 0.1}           # optional, anchor axes; folded into the entity location, see 2.3
  item: couture-rapier             # item_model id. `main_hand` is resolved to its model id at cast start (no cloned stack)
  context: fixed                   # item_display context: fixed | thirdperson_righthand | head | gui | ground | ...
  # --- Transformation(translation, left_rotation, scale, right_rotation)
  translation: [0, 0, 0]           # vanilla translation, entity-local axes
  rotate: [{axis: long, deg: 20}]  # = left_rotation, named-axis list or quaternion
  scale: [1, 1, 1]                 # vanilla scale (item/block/text); sprites use `size`, below
  right: [0, 0, 0, 1]              # right_rotation, quaternion; default identity
  yaw: 0                           # entity yaw/pitch, pinned at spawn, never re-sent; default 0 (all orientation lives in left_rotation)
  pitch: 0
  billboard: fixed                 # fixed | vertical | horizontal | center
  brightness: {block: 15, sky: 15} # absent = world light
  view_range: 1.0
  glow_color: null
  interpolation_delay: 0           # engine-owned after spawn (section 4); written by the author only for hand-tuned loops
  interpolation_duration: 0
  teleport_duration: 0
  for: 24                          # REQUIRED end (for | until | for: cast), section 2.0 and 5; `at`, `every`, `times`, `until`, `lasts` also apply
  motion: [ ... ]                  # laws, section 3
```

`kind: block` has `block: minecraft:white_concrete` instead of `item`/`context`. `kind: text` has `text`, `background`, `alignment`, `line_width`,
`text_opacity`, `see_through`, `shadow`.

**Sprites (`kind: sprite`) are text-display glyphs with a REAL size (no hidden x5).** A sprite file `content/sprites/<id>.yaml` declares
`px: [w, h]` and the loader derives the natural quad size `px x 0.025` blocks, plus the quad centre. The vanilla scale unit for a sprite is
**1 = natural size**. **Decision (SKL-16, recommendation taken): the author writes `size: [w, h]` in BLOCKS**, because "0.4 x 0.3 blocks"
reads the same regardless of the texture's pixel size; the loader divides by the natural size to get the vanilla `scale` and `expand` prints
both (`size [0.4, 0.3] -> scale [1.0, 0.75] of natural 0.4 x 0.4`). Growth laws on a sprite work on `size` (`grow` in blocks per tick). The
centre compensation is the explicit `pivot: center` macro (default `center`), expanded into `translation` and printed; no frame-distance factor
exists (`size_frac` is deleted). Item/block/text displays keep the plain vanilla `scale`.

#### 2.2 `particle`

Maps 1:1 to `spawnParticle(particle, x, y, z, count, offsetX, offsetY, offsetZ, extra, data)`; schema defaults are written down so none comes from an
overload: `count: 1`, `offset: [0, 0, 0]`, `extra: 0`.

```yaml
- {id: puff,  op: particle, particle: dust, anchor: palm, count: 6, offset: [0.1, 0.1, 0.1], extra: 0, color: "#ff5fa2", size: 1.2}
- {id: shoot, op: particle, particle: crit, anchor: muzzle, count: 0, offset: "= anchor.forward", extra: 0.8}   # count 0: offset = DIRECTION, extra = SPEED
```

| field | vanilla argument |
|---|---|
| `anchor` | x, y, z (anchor world origin; `{of: bolt, sweep: 0.3}` = one call per 0.3 blocks of the anchor's travel since the last send, an explicit loop, not a particle argument) |
| `count` | count. `>0`: N particles, `offset` is the gaussian spread box. `0`: ONE particle, `offset` is the direction vector, `extra` the speed |
| `offset` | offsetX/Y/Z (world axes; CEL allowed) |
| `extra` | extra (speed; for dust etc. as vanilla uses it) |
| `color`, `size` | `Particle.DustOptions` (color, size) |
| `block`, `item` | `BlockData` / `ItemStack` data |
| `at`, `for`, `every`, `times`, `until` | the timing block of 2.0 (`every: 1` for a trail); not particle arguments |

Deleted: `dir`, `spread_deg`, `speed` (the 0.3 sphere), `inherit_velocity`, `seed`, "count as repeat" and the `vanilla` flag. A custom particle
definition is a separate op, `particle_def: couture-s2-p-pink` with `repeat`, never an overload of `particle`. The server-side per-call count cap
(64) is a safety limit in server config, not a semantic.

#### 2.3 What is and is not in `translation`

The vanilla order is `T * L * S * R`: `translation` is NOT rotated by `left_rotation`. Therefore:

- The entity location is the anchor's world origin plus `offset` (rotated by the anchor basis). Anchor motion rides `teleport_duration`.
- Rotation about the object's own origin changes only `left_rotation`: one segment per law.
- Rotation about a point other than the object's origin (a pivot, an `orbit`) changes `translation` too; the engine writes the matching
  pair per segment and subdivides so the chord error stays under 0.03 blocks / 2 degrees (worst case one segment per tick, still one write).
- **Billboarded objects (`center`/`vertical`/`horizontal`) carry their offset in the entity LOCATION, never in `translation`** (translation is applied
  in billboarded space and would swing with the camera, RC8). Their spin is a roll about the display normal in `left_rotation`.
- `ViewerAxis` (server-side nearest-viewer facing) is deleted; facing is `billboard` or a fixed basis.

### 3. Motion as rates and laws

Motion is a list on the object (`motion:`) or on an anchor (`motion:` in `anchors`, presentation anchors only). Laws are composable.

Every law has its own window: `from` (ticks since the instance started, default 0), `for` (ticks; or `until: <event>`; default to the end of the
instance), and `ease` (`linear` default, names and total-vs-rate semantics in 3.7). A law is an ordinary part of the timing grammar (2.0): same words, same meaning, relative to the instance. Axes are named (3.1) and RESOLVED ONCE at law start, then frozen in the parent basis (a spinning object does not wobble).
Closed-form: all laws are evaluated as functions of `tau = ticks since law start`, never integrated tick by tick.

| law | fields | meaning |
|---|---|---|
| `spin` | `axis`, `deg_per_tick`, `accel?` (deg/tick^2) | `angle(tau) = rate*tau + accel*tau^2/2` about `axis` |
| `move` | `dir` or `velocity` (name-keyed or CEL), `speed`, `accel?` (blocks/tick^2 along the velocity), `gravity?` (blocks/tick^2 down), `drag?` (fraction/tick) | `v' = (v + a + g) * (1 - drag)` per tick; closed form per segment |
| `orbit` | `center` (anchor), `axis`, `deg_per_tick`, `phase_deg?`, `face?: keep/tangent/center` | position rotates about `center` along `axis`; radius = distance at law start |
| `grow` | `prop?`, `per_tick` (vector or number) + `accel?`, or `to` + `for` + `ease` | change of a property: display `scale`/`size`, particle `count`/`offset`/`extra`, sound `volume`/`pitch` |
| `fade` | `exponent?` (1.0 = linear, see 2.0b), `for` | the intensity property goes to 0: display `text_opacity` (text/sprite; item/block displays have no alpha, use `grow` to zero), particle `count`, sound `volume`. `a = (1 - tau/for) ^ exponent` |
| `align` | `axis: <named>`, `to: velocity \| toward(<anchor>) \| <named axis/CEL vector>`, `up?` | orients the object axis; `to: velocity` of an attached `move` is constant over its segment, so it is ONE write |
| `follow` | `anchor`, `offset?`, `lag?`, `step?` | position tracks another anchor; per-step (live) law |
| `stretch` | `axis`, `from`, `to` (anchors) | length of the object along `axis` equals the distance between two anchors; this is what `line` expands to |
| `keys` | `[{t, pose?, offset?, size/scale?, ease?}]` | irregular choreography only (3.3) |

#### 3.1 Named axes

Resolution order: the object's own axes (`long`, `edge`, `face` for item parts; `face` = display normal for sprites), then its anchor's
(`forward`, `left`, `up`), then `world.up` / `world.forward`. An axis may also be `{in: <anchor>, vec: {forward: 0.2, up: 0.9}}` for a non-named direction (the converter uses this when
nothing is within 5 degrees of a named axis).

#### 3.2 Composition order

For one target, per tick: all TRANSLATE laws first (`move`, `orbit`, `follow`, summed as displacements in list order), then ROTATE laws
(0301's order, kept). Rotation laws and `pose` lists compose **outer to inner**: `rotate: [A, B]` or laws `[spinA, spinB]` give
`R = R_A * R_B * R_rest` (B acts on the object first, A turns the result), the scene-graph reading. Local pose `= T(offset) * R * S(scale)`
in the anchor basis; the send step (section 4) converts it to world: location = anchor origin + anchor.rot * offset, `left_rotation` = anchor.rot * R,
`scale` = S in OBJECT axes. Nesting wheels-within-wheels is done by anchoring an object on another object's anchor.

#### 3.3 Keys (irregular choreography only)

```yaml
keys:
  - {t: 0, pose: [{axis: left, deg: 0}]}
  - {t: 3, pose: [{axis: left, deg: 70}], ease: out}
  - {t: 7, pose: [{axis: left, deg: 130}, {axis: long, deg: 40}]}
```

`pose` is an ABSOLUTE orientation from rest as a short product of named-axis rotations (same grammar as `rotate`), never Euler triples; any rotation
is representable as one `{axis, deg}`. Between keys: slerp for rotation, lerp for offset/scale, `ease` on the segment ENDING at the key. The client only
tweens linearly, so an eased segment is cut into 2-6 linear sub-segments (error under 2 degrees / 0.03 blocks). Multi-turn motion between keys is
not expressible (shortest path); the lint rejects a key pair turning more than 170 degrees and asks for `spin` or a midpoint key.

#### 3.4 The same laws on every kind

A law acts on a **property**, named by `prop`. Defaults per kind are in the table; a law whose type does not fit the property (a `spin` on a
`pitch`) is a lint error. This is what lets one law set drive a display, a particle and a sound together.

| kind | properties (`prop`) | default prop of `grow` / `fade` |
|---|---|---|
| display (item, block, text, sprite) | `offset` (position), `rotate`, `size` (sprite) / `scale`, `alpha` (text, sprite) | `size`/`scale`; `alpha` |
| particle | `anchor` (emission point), `count`, `offset` (vanilla: spread box, or the DIRECTION when `count: 0`), `extra` (vanilla: speed) | `count`; `count` |
| sound | `anchor` (source point), `volume`, `pitch` | `volume`; `volume` |

| law | on display | on particle | on sound |
|---|---|---|---|
| `grow` | `scale`, `size`, `offset` | `count`, `offset`, `extra` | `volume`, `pitch` |
| `accel` (field on `spin`, `move`, `grow`) | rate of the rate: `deg_per_tick`, blocks/tick, size/tick | `extra` (speed) `accel`; `count` rate | `pitch` / `volume` rate |
| `move` / `orbit` / `follow` | `offset` | emission point `anchor`; with `count: 0` also the direction vector | source `anchor` |
| `spin` | `rotate` | the direction vector (`count: 0`) | n/a |
| `fade` | `alpha` | `count` to 0 | `volume` to 0 |
| `align` / `stretch` | orientation / length | direction vector | n/a |

Composition: laws on the same property add their contributions (rates add; rotations compose per 3.2), each only inside its own
`from`/`for` window; a `to:` target blends from the value at window start. Examples: `{law: grow, prop: count, per_tick: 0.5}` (an emit that thickens),
`{law: grow, prop: pitch, per_tick: 0.02, accel: 0.001}` (a rising sound), `{law: move, prop: position, velocity: {forward: 0.3}}` (an emitter that drifts),
`{law: spin, prop: offset, axis: up, deg_per_tick: 12}` (a count-0 particle burst whose direction turns).

How each kind samples a law:

- **Displays**: continuous, sent as segments (section 4).
- **Particles and sounds are fire-and-forget**, so a law is evaluated **per occurrence** (at `at + i * every`) and the occurrence uses that value. A
  continuous glide is therefore an occurrence train: a windup rise is `{op: sound, every: 1, for: 20}` plus `grow pitch`. (Minecraft cannot change a
  playing sound; v1's `pitch_arc` is exactly this.)

#### 3.5 Expressions (CEL) in every numeric or vector field

Every numeric or vector field of every op, law and key accepts `"= <CEL>"` (0301 Decision 5, same cel-java). Variables:

| variable | meaning |
|---|---|
| `t` | ticks since the instance (occurrence) started; `lt`: ticks since the current law's window started |
| `i`, `n` | repeat index of `every` (0-based), and the occurrence count (see 2.0) |
| `ease(name, u)`, `lerp(a, b, k)`, `slerp(qa, qb, k)` | easing and interpolation functions (3.7); `ease` takes any easing name as a string |
| `rand()` | seeded, deterministic uniform [0,1): seed = (cast id, op id, i, call-site order), so replays and the game-sim are identical |
| `forward`, `left`, `up`, `long`, `edge`, `face` | the object's axes as WORLD vectors (anchor axes first, then item part axes) |
| `caster`, `target`, `contact` | positions with `.pos`, `.forward`, `.eye`; `contact.normal`, `contact.point`; `target` is the victim or aim target |
| `row.<name>` | the current `each:` row; `${param}` for params; `anchor.<id>` for any anchor |

Examples: `deg_per_tick: "= 10 + t * 2"`; `offset: {left: "= sin(i * 0.5) * 0.3"}`; `count: "= 4 + i"`; `pitch: "= 0.8 + 0.05 * i"`;
`velocity: "= normalize(target.pos - pos) * 1.2"`.

**When an expression is evaluated, and what it costs in writes** (one class per expression, decided by what it references):

| class | references | evaluated | cost |
|---|---|---|---|
| S, spawn | params, `i`, `n`, `row`, `rand()`, snapshot anchors, `caster`/`target`/`contact` read at the occurrence start | once, at the occurrence's start | none beyond the spawn |
| T, time | S-values plus `t` / `lt` | pre-sampled at spawn on the tick grid; the samples are fitted to piecewise-linear segments with the error budget of section 4 (0.03 blocks / 2 degrees); breakpoints become segment boundaries. A polynomial in `t` (`10 + t*2` as a rate) is integrated exactly | **one write per fitted segment**: a slow or smooth expression costs a few writes, the same as a law |
| L, live | follow-mode anchors, live `caster` / `target` / `contact` motion | at every send step (`step`, default 1) | one write per step |

**A per-tick expression forces per-tick writes.** If a T expression varies faster than the error budget allows for segments longer than one tick
(period of about 3 ticks or less, a `rand()` inside a T field) or the expression is L, the object is written every tick (still one combined
write per object per tick, never split). `expand` prints each object's segment table and the number of writes per tick; the lint warns above 1.
Fields of class S never cost anything.

#### 3.6 Gameplay uses the same grammar: projectile block mode

`projectile` takes the timing block (`for` replaces `max_ticks`) and a `block` field (replaces `block_mode`, `stick_ticks`, `restitution`, `max_bounces`
from `wp/se-grammar` `e5aedb5b0`); `mode` is required, the other fields are required for their mode:

```yaml
- {id: bolt, op: projectile, body: bolt, from: muzzle, velocity: "= look(caster) * launch_speed", for: "${max_lifetime_ticks}", radius: 0.5,
   block: {mode: bounce, restitution: 0.6, times: 3}}      # times = max_bounces
#  block: {mode: stick, for: 28}          # for = stick_ticks: stays stuck 28 ticks, then `unstick`
#  block: {mode: despawn}                 # ends at the block; `pass` flies through
```

| `mode` | other fields | events (usable as `at` / `until`) |
|---|---|---|
| `despawn` | none | `end` |
| `stick` | `for` (ticks stuck) | `stick`, `unstick`, `end` |
| `bounce` | `restitution` (required, 1.0 = elastic), `times` (required, max bounces) | `bounce` (with `i`), `end` |
| `pass` | none | none |

Displays anchored on `bolt` end with `until: end` (or `until: unstick` for a stuck prop) and follow the gameplay frame through the bounce; the
bounce is a gameplay `move` reflection, the display needs no extra law.

#### 3.7 Easing: how anything advances over a window

**One rule, stated once: any value or rate that changes over a window takes `ease`.** It is not attached per kind. It applies to every law on any kind
(display transform, particle `count`/`offset`/`extra`, sound `volume`/`pitch`, ribbon `width_frac`), to `keys` segments, to speed (a `move` law's `speed`, a
`projectile`'s speed ramp: a dash that eases out, a shot that accelerates in), and to **timing itself** (the spacing of `every`, 3.7.1). If a quantity moves from
one value to another across a window, the window's `ease` says how it advances.

Every law (`move`, `spin`, `grow`, `fade`, `orbit`, `stretch`, `align`, `follow` blend, every `prop` law of 3.4) and every `keys` segment takes `ease:`.
`linear` is the identity default (written in the field table, 2.0b). Names are the standard easing set:

| family | names |
|---|---|
| `linear` | identity |
| `in_` / `out_` / `in_out_` + shape | `sine`, `quad`, `cubic`, `quart`, `quint`, `expo`, `circ`, `back`, `elastic`, `bounce` (for example `out_cubic`, `in_out_back`) |
| parametric | `cubic_bezier(x1, y1, x2, y2)` (CSS meaning, x in [0, 1]), `steps(n)` (n equal jumps, jump at the end of each step) |

The 0301 short names `in` / `out` / `in_out` stay valid ONLY as written inside a `keys` segment of the same law and mean `in_quad` / `out_quad` / `in_out_quad`;
the converter writes the full name (R8), so no content depends on the alias.

**Semantics.** Easing maps the normalized progress of the law's own window to the eased progress: `u = lt / for` in [0, 1], `E(u)` the eased value
(`E(0) = 0`, `E(1) = 1`; `back`/`elastic` may leave [0, 1] in between, which is overshoot by design). A law with `ease` other than `linear` MUST
have a finite window (`for`); an open `until:` window has no `u` and is a load error. A `keys` segment uses its own segment-local `u` between the two keys.

**Rate laws: ease shapes the accumulated TOTAL, not the rate** (recommended and adopted). `spin {deg_per_tick: 18, for: 20, ease: out_cubic}` means
"turn `18 * 20 = 360` degrees over 20 ticks, decelerating", `angle(lt) = 360 * E(u)`. The same for the other rate laws:

| law | with `ease` |
|---|---|
| `spin` | `angle = deg_per_tick * for * E(u)` |
| `move` | `displacement = velocity * for * E(u)` (the direction is the frozen axis; `speed` is the average speed) |
| `grow per_tick` | `value = v0 + per_tick * for * E(u)`; `grow to` is `v0 + (to - v0) * E(u)` |
| `fade` | `a = (1 - E(u)) ^ exponent` |
| `orbit` | `angle = deg_per_tick * for * E(u)` |
| `stretch`, `align`, `follow` | blend factor `E(u)` between the window start and end values |

Why total: it is the natural reading ("360 degrees over 20 ticks, ease out") and it is how animators and every tween library think. Shaping the rate
would mean integrating a curve, so the total would change with the curve; with total semantics the end state is fixed by `deg_per_tick * for` whatever the ease.
`ease` and `accel` are mutually exclusive on one law (load error; `accel` already defines the time profile). For an eased law that must also have a
different rate shape, give it two laws with their own windows.

**CEL** (3.5): `ease('out_cubic', u)` returns `E(u)` (names as above, `ease('cubic_bezier(0.2,0.8,0.2,1)', u)` for a parametric one), `lerp(a, b, k)` linear
for numbers and vectors, `slerp(qa, qb, k)` for rotations. Example: `offset: {up: "= lerp(0.2, 1.4, ease('out_back', t / 10))}"`. The `t` argument makes this a T-class expression (3.5).

**Static expansion and the client.** An eased law is a **T-class** expression: a closed-form function of the law's own `u`, evaluated at expand time. The vanilla client
interpolates LINEARLY within `interpolation_duration`, so the curve is reproduced by sending a chain of linear segments:

1. Split the window into `m` equal sub-segments (`h = for / m` ticks). Linear interpolation error on a segment is at most `h^2 * max|f''| / 8` where `f''` is the
   second derivative of the eased quantity (value, degrees or blocks). `m = ceil(for / h)` with `h = sqrt(8 * budget / max|f''|)`; the budget is the section 4
   error budget (0.03 blocks / 2 degrees; for a unitless property, 1 percent of its range).
2. `m` is clamped to `[1, for]`. If `m >= for / 2` the object is simply written EVERY tick (`h = 1`); a tick is the finest granularity anyway.
3. **Write count rule**: writes of an eased law = `m + 1` including the first (the law's start and the `m` segment ends), and these boundaries are shared with the other
   laws and keys of the same object (one write per object per boundary, 4). Smooth curves (`sine`, `quad`, `cubic`, `circ`) of rotation up to 360 degrees need about 3-6 segments; `back`,
   `elastic`, `bounce` (large `f''`) usually reach the per-tick rule; `steps(n)` is exactly `n` writes with `interpolation_duration: 0` (a jump, no sub-segments).
4. The sub-segment endpoints are sampled from `E`, so the first and last of the window always match the unmodified end values exactly.

`expand` (11) prints, for each eased law, `m`, `h`, the max error and the writes, and the lint warns above one write per tick as it does for any T expression.

#### 3.7.1 Speed and spacing take the same `ease`

- **Speed**: `move {speed, for, ease}` follows the total rule (`displacement = velocity * for * E(u)`), so `ease: out_cubic` is a dash that decelerates and `in_quad` is
  an acceleration. `projectile` takes `speed: {from, to, for, ease}` for a speed ramp (a shot that accelerates in): the speed at lt is `lerp(from, to, E(lt / for))`
  and the path is the exact integral, computed at expand time (a T expression); the gameplay body and the display share it.
- **Spacing (timing)**: `every` may be a range, `every: {from: 6, to: 2, ease: in_quad}`: the period shrinks from 6 to 2 ticks across the op's `for` window. The fire ticks are computed
  STATICALLY at expand time by `t0 = at`, `t(k+1) = t(k) + max(1, round_half_up(lerp(from, to, E((t(k) - at) / for))))`, while `t(k) < at + for` (bounded by `times` if given), and listed in the table (11).
  A plain number `every: M` keeps the exact-multiples rule of 2.0 and is the same as `from == to`, `ease: linear`. Spacing ease needs a finite `for` (an `until`-only window is a load error, as for any ease).

**Particles and sounds** (fire-and-forget, 3.4): the law is sampled at each occurrence's `lt`; an eased `count`/`offset`/`extra`/`volume`/`pitch` changes the sampled value per
occurrence and needs no segments; the occurrence train IS the sampling.

### 4. Sending to the client (RC1)

Per object the engine keeps the last sent state and sends **segments**. One send is ONE entity-metadata write containing, together,
`Transformation` (translation, left, scale, right), `interpolation_duration = d`, `start_interpolation = 0` (+ `teleport_duration = d` and the
teleport in the same tick when the location moves). **Duration and transformation are never written separately, and rotation and scale never
have different durations** (RC1 root cause). The payload is the state at `s + d`, sent at tick `s`, with the continuity invariant that the
previous segment ended at `s` exactly; no "sample at tick+1" compensation exists.

Re-send happens only at:

1. spawn: state(0) is written at creation with duration 0 (hidden until the first segment, no pop); the first segment is sent on the NEXT tick;
2. a law/key boundary: law `from`/`until`, a key time, an eased sub-segment boundary;
3. an error-budget boundary inside a law: gravity/drag arcs, pivot-offset arcs, `orbit` (budget 0.03 blocks / 2 degrees; at most 59 ticks per segment,
   the teleport_duration limit);
4. the law set changing: event, `reparent`, parameter change, `remove`;
5. reactive laws (`follow`, `align` toward a moving anchor, a mounted caster's heading drift): every `step` ticks (default 2, minimum 1); a live (class L) or high-frequency T expression (3.5) is written every tick;
6. a gameplay anchor's reactive motion: every tick.

Constant-rate cases (coin 2's spinning shears, petals, streaks) therefore cost ONE write per law for the whole spin. Client latency jitter
(one tick) is accepted. A game-sim gateway that models the client (value shown at tick t = lerp(prev, target, min(1, (t - writeTick)/d)) and checks
authored-vs-shown rotation and position) becomes the shipped gate (BS1/BS2).

### 5. Lifetime (RC5)

**Every object declares its end in the timing block (2.0); an end (`for`, `until`, or `for: cast`) is REQUIRED on `display` and `ribbon`, and a
bound (`for`, `times`, or `until`) on any repeating op; a skill without it fails to load.**

```yaml
for: 24                      # n ticks after the start
until: release               # first time the event fires
for: cast                    # explicit: until the cast ends (cancel, death, disconnect and unload count as the end)
```

- Owner is the cast. The runtime registry is keyed by cast id; cast end/cancel/death/disconnect/unload removes everything owned. A cap
  (`max_life_ticks`, server config, default 1200) applies even to `for: cast`.
- Every spawned entity carries the PDC tag `astera:cast=<uuid>`; at boot and on chunk load a sweeper removes tagged entities with no live owner.
- `remove: [ids]` stays for early ends; it is never the only end.
- **Converter carries the v1 end (patch on wp/se-streak, `bea1b2641`, added a remove by hand because the converter read `end_tick` but
  dropped it for plates):** `for = end_tick - start_tick`; an element WITHOUT `end_tick` takes the v1 effect's `duration - start_tick`
  (v1 despawned the whole effect at its duration). The converter FAILS (does not warn) when it cannot determine an end.
- Content known or suspected to rely on a missing end, to be confirmed by the load-time check on first run of this schema (not yet audited by
  capture): `couture-rapier-s2` coin 1 streaks (confirmed by founder); `bastion-greatsword-def` `bastion-rim`/`crest` (5 plates, one remove at
  tick 162 while the v1 elements live 71-99 ticks); `orrery-s2` orb/impact/glint (plates `impact_violet_flash`, `impact_long_violet_flash`, annulus
  removed only by event handlers that fire on hit; staff trails, emitter light removed at fixed ticks).

### 6. Converter strategy (`tools/skill-convert`)

Input v1 vfx element tracks: per-key `offset`, `rotation` (Euler yaw/pitch/roll in the v1/0301 convention `Ry(-yaw) Rx(pitch) Rz(roll)`), `scale`.
Output laws first, keys last.

| # | rule |
|---|---|
| R1 | Unwrap each Euler channel (309, 349, 509 are continuous), convert keys to quaternions, then process per channel (rotation, offset, scale) independently. |
| R2 | Per consecutive key pair compute the segment. **Two keys are a single segment = one constant-rate law by definition.** |
| R3 | Adjacent segments merge when rate is within 1% (or 0.5 deg/tick; 0.005 blocks/tick) and, for rotation, the axis is within 1 degree. |
| R4 | Rotation run -> `spin {axis, deg_per_tick, from, ticks}`; offset run -> `move {velocity, from, ticks}`; scale run -> `grow {per_tick, from, ticks}`. Non-zero starting values become the object's static `rotate`/`offset`/`size`. |
| R5 | Axis snapping: a rotation axis within 5 degrees of an object/anchor axis uses that name (sprite roll is `face`); otherwise `{in: <anchor>, vec: {...}}`. |
| R6 | A run of constant second difference in the world-up direction -> `move` with `gravity`; otherwise not merged. |
| R7 | Everything that remains -> `keys` with named-axis `pose` lists (rotation as one `{axis, deg}` from rest, snapped by R5) and name-keyed offsets. Never Euler triples. |
| R8 | v1 `ease` stays on the law/key segment as `ease`, written with the full name (`in` -> `in_quad`, `out` -> `out_quad`); a segment with no v1 ease gets `linear` explicitly. A v1 sampled curve that matches a standard ease within the error budget is recognised and written as `ease:` (otherwise it stays `keys`). |
| R9 | Generated point frames (`pt_caster_0_m0p2_1`) become inline `{at, offset: {...}}` on the object; no synthetic frame ids. `like: contact` hit anchors default to the contact point (RC6). |
| R10 | v1 particle parts map 1:1 to `particle` (count, offset, extra, data, count 0 included). No default sphere, no `vanilla` flag. |
| R11 | Sprite sizes are written as `size` in blocks from the v1 plan and the sprite file's `px`; no x5, no frame-distance factor. |
| R14 | **v1 parts map to states**: each v1 timeline group becomes a state (`for` = distance to the next group's `at`, `then` = the next state); `window`+`do` becomes a state with a `hurt` transition; `delay` becomes a state or an `{after}` transition; event handlers (`hit`, `block`, `expire`, `reel`, `arrive`) become transitions with the handler ops as transition `do`/target `enter`. Sequences like coin1 -> raise -> flight -> stuck -> reel -> coin3 become a linear machine plus event transitions. |
| R15 | **Part extraction**: when the converter finds the same op-subgraph (after frame renaming) in two or more skills (guard windows, dashes, projectile + block handler), it emits it once to `content/skill-parts/*.gen.yaml` and replaces the sites with `use:`. Parameters are the values that differ; none is defaulted: every parameter is `required`. A part that cannot be proven identical is NOT extracted (kept inline; no fuzzy matching). |
| R17 | The converter emits only the declarative form (`at`/`for`/`every`); it never writes `seq:`. After conversion it runs `expand` and stores the table next to the skill for review. |
| R16 | **Lint closes the loop**: the converted machine must pass the static checks of 10.4 (the converter fails, not warns, on an unterminated state or an unowned display). |
| R12 | `end_tick` becomes `for` (section 5); v1 `start_tick` becomes `at`, v1 loops (`every`/`for`) become the same fields on the op, and v1 non-default values such as `fade_power: 1.5` are written explicitly (2.0b). |
| R13 | **The PROTECTED set is dropped.** Hand edits live in a `kind: Patch` document keyed by `id` (0301 composition); the converter writes only `*.gen.yaml`, so re-conversion never touches hand-written files and nothing needs to be skipped. |

Worked check (coin 1 petal 0): offset forward 1.68 -> 1.88 over 1 tick, then -> 3.52 over 4 ticks => `move 0.2/tick` for 1 tick then `move 0.41/tick`
for 4; roll 309 -> 349 -> 509 => `spin face 40` for 5 ticks (rate equal in both segments, merged by R3, start 309 static); scale 0.026 -> 0.064 -> 0.045 =>
`grow +0.038` for 1 tick then `grow -0.0048/tick` for 4. The petal goes from 12 numbers to 4 laws.

### 7. Examples

Full examples are in `docs/adr/0303-examples/`:

- (a) [a-couture-rapier-s2-coin1.yaml](0303-examples/a-couture-rapier-s2-coin1.yaml): coin 1, rewritten from `coin1.yaml`.
- (b) [b-frost-shard-projectile.yaml](0303-examples/b-frost-shard-projectile.yaml): a simple-shoot projectile skill (`frost-shard`).
- (c) [c-bastion-greatsword-s1-swing.yaml](0303-examples/c-bastion-greatsword-s1-swing.yaml): a melee sweep with a weapon swing (`bastion-greatsword-s1`).
- (i) [i-easing-display-particle-sound.yaml](0303-examples/i-easing-display-particle-sound.yaml): a display, a particle count and a sound pitch with eases in one timing block.
- (h) [h-same-effect-two-notations.yaml](0303-examples/h-same-effect-two-notations.yaml): one effect written declaratively and with `seq:`, with the identical expanded table.
- (e) [e-couture-rapier-def-parry-part.yaml](0303-examples/e-couture-rapier-def-parry-part.yaml): the `parry` part and `couture-rapier-def` calling it with its riposte branch.
- (f) [f-shoot-with-bounce-and-explosion-parts.yaml](0303-examples/f-shoot-with-bounce-and-explosion-parts.yaml): a shoot skill calling `projectile` (block mode `bounce`) and `explosion` on contact.
- (g) [g-couture-rapier-s2-states.yaml](0303-examples/g-couture-rapier-s2-states.yaml): the S2 coin sequence as states.
- (d) [d-shared-timing-and-laws.yaml](0303-examples/d-shared-timing-and-laws.yaml): a sound, a particle and a display sharing one timing block and one law set.

Sizes in the examples are illustrative: the converter computes them from the sprite files; shapes, rates and timings are taken from the current files.

### 8. Old field -> new field -> vanilla argument

| old (0301 / converter) | new | vanilla argument |
|---|---|---|
| `mesh {frame, item, part, scale, pivot, rest, tilt}` | `display {kind: item, at, item, context, rotate, translation, scale}` | `ItemDisplay` item_model + `item_display` context + Transformation |
| `plate {parent, face, sprite, size_frac, alpha}` | `display {kind: sprite, at, sprite, size, billboard, fade}` | `TextDisplay` glyph + scale + billboard + text_opacity |
| `face: {viewer: center}` / `vertical` | `billboard: center` / `vertical` | `Display.Billboard` |
| `face: {plane: {frame, axes}}` | `billboard: fixed` + `rotate` over named axes | `left_rotation` |
| `face: {viewer: axis}` (ViewerAxis) | deleted; `align` + `billboard` | |
| `line {from, to, thickness}` | `ribbon {from, to, thickness}` = display + `align`/`stretch` laws | scale along the stretched axis + `left_rotation` |
| `trail {of, root, ticks, ...}` | `ribbon` over the anchor history, `for` / `until` | chain of display segments |
| `track {keys: [{offset, rotation: euler, scale}]}` | `motion: [spin/move/grow]` or `keys` with `pose` | `Transformation` segments, `interpolation_duration` |
| rotation `[yaw, pitch, roll]` | `{axis, deg}` list or quaternion | `left_rotation` |
| frame offset `[left, up, forward]` | `{forward, left, up}` map | `translation` / entity location |
| `pose {rotate: {axis, deg}}` on a display | `rotate` / `keys.pose` | `left_rotation` |
| `emit {count, dir, spread_deg, speed, inherit_velocity, seed, vanilla}` | `particle {count, offset, extra, color/size/block/item}` | `spawnParticle(count, offX, offY, offZ, extra, data)`; count 0 = direction mode |
| `emit ... spacing: s` | `particle anchor: {of, sweep: s}` | one call per sample |
| custom `emit particle: <def>` | `particle_def: <def>` | n/a (engine particle) |
| `updatePose`/`updateScale` (2-tick scale, 1-tick pose) | one segment write | `interpolation_duration` |
| `HeldItem` mesh (cloned stack, THIRDPERSON_RIGHTHAND) | `item: main_hand` resolved to model id, `context: fixed` (open decision 1) | `item_display` context |
| world-teleported "attached" mesh | `mount: caster` | passenger |
| `size_frac` (x parent frame distance), glyph x5 | `size` in blocks | `scale` |
| timeline group `every` loops, separate `sound every`, `emit spacing` loops, `at` + `until` scattered | the one timing block `at / for / every / times / until / lasts` on every op | n/a |
| `fade` default 1.0 (v1 `fade_power` 1.5) | `fade.exponent` identity 1.0, converter writes 1.5 explicitly | n/a |
| `projectile {block_mode, stick_ticks, restitution, max_bounces, max_ticks}` | `projectile {for, block: {mode, for, restitution, times}}` | n/a (gameplay) |
| literal-only numbers | any numeric/vector field accepts `"= CEL"` with `t`, `i`, `n`, `rand()`, axes, `caster/target/contact` | n/a |
| (new) free `wait`/`seq` sequences | `for N, every M` is primary; `seq:` is sugar that desugars to the same ops, equality asserted by lint | n/a |
| (new) eased spacing and speed ramps | `every: {from, to, ease}`, `projectile speed: {from, to, for, ease}`, `move ease`; fire ticks listed in the expansion table | n/a |
| (new) per-law `ease` names | `ease: linear\|<in_\|out_\|in_out_><shape>\|cubic_bezier(..)\|steps(n)`; ease shapes the accumulated total (3.7) | n/a, sent as linear segments within the budget |
| (new) L-expression timing | `bound: [lo, hi]` declared or load error (11) | n/a |
| v1 part: `sequence` timeline groups, `window`, `delay`, `schedule`, event handlers (`on: hit/block/expire/reel/arrive`) | states with `for`/`then`, transitions, nested parts | n/a |
| ad hoc repeated blocks copied between skills (a dash, a guard, a projectile with block handling) | `use: <part>` with typed `with:` and `frames:` binding | n/a |
| `incoming_hit` window `{ticks, filter}` | state `{for, on: [{event: hurt, when}]}` (10.3) | n/a |
| skill invoking another skill (not possible) | `use: skill:<id>`, ungated unless `gate:` is written | n/a |
| missing `end_tick` / `lifetime = 1_000_000` | `for` / `until` required, cast registry, sweeper | n/a |

### 9. Composition: parts and skill calls (MythicMobs metaskills, typed)

A **part** is a reusable component in `content/skill-parts/*.yaml` (`kind: Part`). A skill, or another part, calls it with `use:`. It is the
metaskill idea of MythicMobs, but typed: parameters and events are declared, frames are bound explicitly, and everything is checked at load.

```yaml
kind: Part
id: parry
params:                                   # typed; each is `required: true` or has an identity default written here (2.0b)
  window:   {type: ticks,  required: true}
  range:    {type: blocks, required: true}
  reduction:{type: number, default: 0}          # identity: no damage reduction unless the caller says so
frames_in:  {weapon: anchor, body: anchor}       # what the caller must bind
frames_out: {riposte_tip: anchor}                # frames the part publishes to the caller (read-only)
emits: [parried, whiff, end]                     # the events the caller may react to; `end` is implicit and always emitted
machine: { ... }                                 # a part IS an automaton (section 10)
```

```yaml
- {id: g, use: parry, with: {window: 30, range: 3.5, reduction: "= 0.7"}, frames: {weapon: sword, body: body}, at: 0, for: 40}
```

**Rules.**

| topic | rule |
|---|---|
| Arguments | typed; a missing required argument or a type mismatch is a load error. Any argument may be CEL (3.5); its class (S, T, L) is judged in the CALLER and fixed at the call, so `with: {window: "= 20 + i"}` is evaluated per call. There is no argument with a hidden default (2.0b). |
| Timing | a call carries the ordinary timing block (2.0): `at`, `for`, `until`, `every`, `times`. Repeating `use:` is a part call per occurrence. |
| Scope of time | **`t` inside a part is local** (0 at the part's start; `lt` is local to the current state). The caller's clock is `parent.t`, the caster's `cast.t`. `i`, `n` are the part's own occurrence index and count. |
| Frames | **a part sees ONLY the frames it was given** (`frames:`), plus `caster`, `world`, `contact`/`target` of the current event. No ambient capture of the caller's ids. This is what makes one `parry` look right on any weapon: its visuals are written against `weapon.long`, `weapon.edge`, `weapon.tip` and the bound frame supplies them. A frame binding is by reference (a follow-mode anchor stays live, a snapshot stays frozen), checked against the declared `frames_in` type. |
| Events | a part's `emits` are visible to the caller as `<call id>.<event>` (`g.parried`) in `on:` transitions (section 10). Everything the part does not emit is invisible to the caller. |
| Lifetime | **a part ends no later than its caller's `for`/`until` window and no later than its owning state.** Cancelling the caller (cancel, death, disconnect, state exit) cancels every child, depth first, running the children's exit actions and removing their displays. A child emits `end` last. |
| Depth / recursion | the static call graph (part -> part, skill -> skill) must be **acyclic**: recursion is a load error. Maximum nesting depth 6 (`max_part_depth`, server config). |
| Determinism | `rand()` inside a part is seeded by (cast id, call path, occurrence index, call-site order), so the same part called twice differs in each call yet replays identically. |
| Expansion | the structure is **expanded at load time**: the part's states are inlined as a sub-automaton with ids prefixed by the call id (`g/windup`), so there is no runtime lookup and nothing allocates per call except the instance state (current state, timers, `with:` values). Arguments of class S are evaluated at the call; T and L arguments follow section 3.5. The static checks of section 10.4 run on the expanded tree. A call inside a repeating group creates a new instance per occurrence; the number of live instances is bounded by `for`/`times` and checked. |
| Skill to skill | `use: skill:<id>` calls a whole skill with the same mechanism (it is its machine, with its own params and `emits`). **Cooldown and resource cost do NOT apply to a called skill** unless the caller writes `gate: {cooldown: true, cost: true}` (identity: not gated, written explicitly). A called skill's item requirement is not checked either. |
| Gameplay vs presentation | a part is both: its gameplay effects (section 10.3) and its displays/sounds/particles are in the same machine. Presentation only parts (a slash arc) simply have no gameplay effects. |

Cost: load-time only for structure (linear in part size per call); runtime cost is the same as writing the ops inline, which the lint prints
per call (`expand` shows the flattened machine and the writes per tick).

### 10. State machine: every skill and part is an explicit automaton

A skill or part body is a **hierarchical finite automaton** (Harel statechart). `timeline`, `window`, `when`/`if`/`choose`, `until: <event>` and
`schedule`/`delay` fold into it; there is exactly one way to say "then", "while" and "if this happens".

```yaml
machine:
  start: windup
  states:
    windup:                              # a state
      for: 6                             # per-state duration -> implicit timer transition
      then: active                       # shorthand for on: [{timer: end-of-for, to: active}]
      enter: [ ... ]                     # entry actions (ops, timing relative to entering; `t` = 0 here)
      do:    [ ... ]                     # ops that live in this state (full timing block 2.0, relative to state entry)
      exit:  [ ... ]                     # exit actions, run on EVERY way out (transition, preempt, cancel)
      on:                                # transitions, first match in list order wins
        - {event: input.sneak, to: cancelled}
    active:
      for: 4
      on:
        - {event: hit, when: "= event.victim != caster", to: recovery, do: [{op: damage, ...}]}
      then: recovery
    recovery: {for: 12, then: end}
    cancelled: {final: true}
    end:      {final: true}
```

#### 10.1 Elements

| element | meaning |
|---|---|
| state | named; has `enter`, `do`, `exit`, `on`, `for`, `then`, optional `final: true`, optional nested `machine:` (sub-automaton) |
| transition | `{event or timer, when?, to, do?}`; `when` is a pure CEL guard; `do` are the effects run between exit and entry (in order: exit of source, transition `do`, entry of target) |
| timer | `for: N` + `then:` on a state, or `{after: N, to: ...}` as a transition; `N` may be CEL (class S at state entry) |
| event | `input.<key>` (use, attack, sneak, swap), `hit` (the caster dealt damage), `hurt` (the caster is struck; was `incoming_hit`), `contact`, `block`, `bounce`, `stick`, `unstick`, `cancel`, `end`, plus any `emits` of a bound part (`<call>.<event>`) and `emit: <name>` ops |
| entry / exit action | an ordinary op: display, sound, particle, `use`, `damage`, `status`, `impulse`, `resource`, `emit` |
| owner | every op listed under a state is OWNED by it: displays, ribbons, sounds, parts end when the state is exited (a tighter `for`/`until` of its own may end it earlier). There is no `remove` needed for ownership. A global `world:` list owns things for the whole machine. |
| final | a state with `final: true`; entering it ends the machine (and emits `end` to the parent) |

**Hits, damage and resources are effects, not free-floating rows**: `damage`, `status`, `impulse`, `knockback`, `heal` and `resource` ops are valid
only in `enter`, `do`, `exit` or a transition `do`. A skill's `meta.cooldown_ticks` and `resource_cost` are the gate effects of `use` at cast start (not states).

#### 10.1b Gameplay ops used in states (carried from 0301 unchanged except for the timing block)

All take the timing block (2.0); their own fields keep the 0301 meaning. `YAML` note: the key `on` is a plain string (kaml, YAML 1.2); a YAML 1.1 loader reads it as `true`, so tools parse with a 1.2 loader.

| op | fields |
|---|---|
| `hit` | `shape` (one of `sector: {deg, range}`, `sphere: <radius>`, `capsule: <radius>` with the frames' `from`/`to`), `damage`, `window?` |
| `damage` | `target`, `components: [{kind, amount}]`, `ignore_invulnerability?` |
| `status` | `target`, `status`, `stacks`, `for` |
| `modify_attribute` | `target`, `attribute`, `operation`, `amount`, `for` |
| `impulse`, `knockback`, `heal`, `resource` | as 0301 |
| `caster_anim` | `anim` |
| `dash` | `speed`, `for` (caster movement, as 0301 `dash_strike`) |
| `reel` | `anchor`, `to`, `speed`, `ease?` (as 0301 `reel`) |
| `emit` | `name` (an event for transitions) |

#### 10.2 Hierarchy and event flow (Harel)

- A state may contain a nested `machine:` (or a `use:` call, which is a nested machine). Entering the state enters the child's `start`.
- **Event propagation, child -> parent**: an event is offered to the innermost active state first; if no transition there matches, it bubbles to the
  parent state, and so on to the root. A child's `final` state emits `<id>.end` to the parent. A part's declared `emits` are the events that cross the boundary.
- **Preemption, parent -> child**: a parent transition that leaves the state exits the nested machine first (children's exit actions run, innermost
  first), so a parent `cancel` kills every child. A child never outlives the state that owns it.
- **Time**: each active state has its own local clock `lt`; the machine has `t` (since the machine started); a part has `t` local (section 9).
- **Concurrency**: orthogonal regions are `parallel: [machineA, machineB]` on a state: all regions run, the state ends when all reach `final`
  (or `until:`). Used for "dash body + trail display", not for gameplay state.
- Event delivery is per tick, in a fixed order: input, gameplay events (hit, hurt, contact), timers, then guards re-evaluated. At most
  one transition fires per machine level per tick; the next fires next tick (no instantaneous cascades without a tick, which would loop).

#### 10.3 Old constructs fold into the machine

| old (v1 / 0301) | new |
|---|---|
| `timeline: [{at, do}]` | states in sequence: each group is a state with `for` / `then`, or `do` ops with `at` inside one state |
| `window {listen: incoming_hit, ticks: 30, filter, do}` | state `parry_window` `{for: 30, then: whiff, on: [{event: hurt, when: filter, to: riposte}]}` |
| `delay {after: 7, do}` | state `riposte {for: 7, exit: [damage]}` or a transition `{after: 7}` |
| `when: "= cond"` on an op | the op moves to the state where `cond` is meaningful; remaining runtime conditions become transition guards `when:` |
| `if` / `choose` | sibling transitions with guards in priority order (`on:` list is the choice); the last one without a guard is the else |
| `until: <event>` | the state's `on: [{event, to: next}]`; the owned ops end on exit |
| `schedule {after, event}` / `on: reel:` handlers | `{after: N, to: reel}` transition and the `reel` state |
| `repeat` loops | timing block `every`/`times`/`for` on an op; a loop over states is only legal with a counter guard `when: "= i < n"` or a timer (10.4) |
| `op: event name:` | `emit: name` as an effect; the receiver is a transition `on: {event: name}` |
| `on: {hit, block, expire, bounce, arrive}` | transitions on those events; `arrive` is `end` of the `reel` part |

#### 10.4 Static checks at load (content lint)

1. **Reachability**: every state is reachable from `start` (transitions, `then`, nested starts).
2. **Termination**: every path from `start` reaches a `final` state; a state with no outgoing transition and no `for`/`then` is an error.
3. **No unbounded loop**: any cycle in the transition graph must contain a transition guarded by a counter or `times` bound, or a state with
   `for` (a timer); otherwise the load fails.
4. **Ownership**: every `display`, `ribbon`, `sound` with `every`, looping `particle` and `use` is inside a state (or `world:`), so something owns and ends it; the existing `for`/`until` end rule (5) remains and is checked on top.
5. **Events**: every `on: {event}` names an event that exists (built-in, a declared `emits`, or an `emit:`), and every `emits` is emitted somewhere.
6. **Parts**: the call graph is acyclic and shallower than `max_part_depth`; arguments match their declared types; every `frames_in` is bound.
7. **Effects**: gameplay ops appear only where 10.1 allows; every `damage` is reachable through at least one transition (no dead damage).

#### 10.5 Presentation stays mathematical

States do not change the visual grammar: displays use frames + laws + CEL (sections 1-3, 3.5). A state's visuals are written against bound frames
(`weapon.tip`, `body`), with `t` / `lt` as the clock; the automaton only decides WHEN a display lives and WHICH set is active.

### 11. Static expansion: the timeline is computed at load (compile-time timeline)

Founder 2026-10-28: behaviour should be readable and calculable at the compile stage. At load, every skill and part, with each of its states,
is expanded to a **computed timeline table** by evaluating the timing blocks (2.0), the laws' windows and every CEL expression that can be evaluated.

**The table** (per state, and per call of a part, using the expanded tree of section 9):

| column | content |
|---|---|
| firings | for each op: the exact tick of every firing relative to state entry (`at + k * every`, k < times / bounded by `for`) and the count |
| lifetimes | for each display, ribbon, sound, part: start tick, end tick (or the state exit), and the owner state |
| duration | min and max of the state (`for`, or the bounded window of its event transitions) |
| hits | number of hit/damage firings per state, and the sum per path |
| branches | the event transitions as symbolic branch points with a bounded window (below) |
| writes | the segment count and writes per tick of each display (4) |
| path length | worst-case (max) and best-case (min) total ticks for every path through the automaton from `start` to a `final` state |

Event-driven transitions (input, hit, contact, parried, hurt) stay symbolic; each is a **branch point with a bounded window**, for example:

```
state parry_window   ticks 0-8   branches: hurt[range<=3.5] -> riposte | timer(8) -> whiff
  firings: sfx_guard @0 x1 | guard_plate display 0-8 | resist modify @0 x1
state riposte        ticks 0-7   (entered from parry_window.hurt at 0..8)  hits: 1 @7
state whiff          ticks 0-6
paths: parry_window->riposte->end = 8+7+... min 7 max 15 | parry_window->whiff->end min 14 max 14
```

**Where it appears**: the `expand` CLI and lint output (`tools/skill-convert expand <skill>`), and the skill-compare page (a table beside each skill's video, so the founder can read durations, hits and branches without playing it). It is generated, never hand-written, and a sim scenario asserts it against the engine's actual firing ticks.

**CEL classes (3.5) at expand time**

| class | expand-time treatment |
|---|---|
| S (spawn) | evaluated at expand time wherever its inputs are static (params, `i`, `n`, constants); `rand()` is evaluated with the seeded stream so the table is exactly what a cast produces for the sample seed; the table lists a range when the input is a caster-dependent value |
| T (time) | evaluated at expand time: sampled and fitted (3.5) so segment counts are exact |
| L (live) | marked `runtime-only` in the table and **must declare a bound**: `count: {expr: "= live_count", bound: [1, 12]}` or `for: {expr: "= dist(caster, target) / 0.8", bound: [2, 20]}`. The table uses the bound for min/max. |

**An op whose timing depends on an unbounded L value is a load error** (no declared `bound`, or the value falls outside it at runtime: the runtime
clamps and logs, the lint cannot prove it). This is what lets min and max be computed for every path; and it extends the termination rule of 10.4 (check 8).

#### 11.1 Two notations, one result

- **Primary (recommended): declarative.** `for N, every M`: "for 24 ticks, every 4 ticks, do X". The expanded table follows by arithmetic; there is nothing to simulate, and the author sees the cadence in one line.
- **Sugar: imperative sequence** `seq: [{wait: 1}, X, {wait: 3}, Y]`, allowed in a state's `do`. It means "at the running offset, fire X; add the `wait`s". It is **only sugar**: the loader turns it into the same declarative ops (`at` per op) before anything else.

Both expand to the **identical table**. A check enforces it: the content lint (and a game-sim scenario) takes every `seq:` and asserts that the declarative form it
desugars to produces the same table as the `seq:` itself, tick for tick; a mismatch fails the load. Example h shows one effect written both ways with the same expanded table.

**Recommendation for content: the declarative form by default.** Reasons: (1) cadence and extent are readable in one place ("every 4 ticks for 24"), where the imperative form spreads them across `wait`s; (2) edits are local (change `every: 4` to `3`), where an imperative edit changes every later tick; (3) it expands by arithmetic, with no order dependence, so the lint can compare and diff tables; (4) it is what the converter emits and the laws use. Use `seq:` only for a short one-off sequence of distinct beats where each step differs (for example hit-stop, then flash, then sound) and the count is 2-4.

#### 11.2 Static check 8 and what the table also gives

8. **Bounded timing**: every `at`, `for`, `every`, `times`, `until` resolves to a bound at expand time (S/T evaluated, L declared). Unbounded timing is a load error.

The same table also feeds: the writes lint of 3.5, the min/max path lengths used for cooldown and animation-lock sanity checks (a skill whose
worst-case path exceeds `max_cast_ticks` fails), and the Board's per-item "durations and hits" evidence.

## Consequences

### Positive
- `coin1.yaml` shrinks from 621 lines of per-tick dumps to named objects with one or two laws each; reading it says what moves, how fast, and
  which way.
- The client does the tweening; one write per segment; no write-order bugs. Rotation, scale and position agree in time (RC1).
- Weapons on the caster are passengers (RC2); no hidden scale constants (RC3 basis stated, RC4 particle args 1:1, RC7 one handedness, RC8 no
  server-side viewer facing). Leaks are structurally impossible (RC5). The PROTECTED set and hand-fix patches disappear (RC6).
- The patches listed in the audit section 3 (`VfxNoStretch` caps, `updateScale` constants, `+1` compensation, `ViewerAxis`, `HeldItem`,
  default 0.3 sphere) are removed rather than guarded.

### Negative / Trade-offs
- Breaking change to every converted skill and to the presentation half of 0301 (accepted: pre-release posture, no shims).
- Pivot-offset rotation and gravity arcs cost more writes than constant rates; reactive laws cost one per step.
- The passenger attachment height and heading behaviour of mounted displays are calibrated, not derived; they need a real-client capture.
- CEL in every field makes the write count data-dependent; the lint prints it and warns above one write per tick per object.
- Text opacity has no native tween path (unverified); `fade` on sprites steps per tick.

### Mitigations
- The client-model gateway in game-sim and the leak gate (LeakTrackingVisuals) become mandatory for every shipped skill; `make vfx-shot` compare for
  the six reference skills per integration window.
- `astera skill expand` prints every derived vanilla value (scale from size, centre translation, passenger offset, segment table).

## Alternatives Considered

- **Parts as macros (text substitution)**: rejected; no types, no events, no scoping, and errors surface in the expansion. Typed parts with frame binding give the parry-on-any-weapon property and checkable events.
- **Skill chaining by `event` only (v1 style)**: rejected; it has no hierarchy, no preemption, no ownership, and every cancel path must be repeated.

- **Keep per-tick keyframes, fix the engine only**: leaves the authoring unreadable (founder rejection), and nothing says what a rate is.
- **Euler triples with an explicit order flag**: still three coupled numbers; named axes read and compose better; Euler path differs from slerp near
  90 degrees pitch.
- **Author-written vanilla `scale` for sprites**: one fewer macro, but each sprite's natural size must be known to read it. Rejected for sprites,
  kept for item/block/text.
- **Always entity-yaw/pitch for orientation**: heading changes force per-tick teleports and give no roll; orientation stays in `left_rotation`.
- **Per-tick sends with `interpolation_duration: 1`**: simple, but every tick is a packet per object and the latency/jitter budget is spent on every
  tick (this is the 0301 behaviour).

## Open decisions (founder)

- **Name collision `at` (resolved in rev3)**: `at` means the timing start tick ONLY; the object's parent anchor is `anchor:` everywhere (anchors, displays, particles, sounds, ribbons, parts' `frames`).
- **`then_event` and `bound:` spellings** in examples f/g are provisional; the grammar is `on: [{event: <call>.end, to}]` and `bound: [lo, hi]`.

1. **Held-item context, RC3.** `fixed` (recommended) vs `thirdperson_righthand`. `content/frames/couture-rapier.yaml` points are measured in the
   `fixed` basis and the three pieces of coin 2 share it; `thirdperson_righthand` (`held_hand` part in the frames file) matches how the real held
   weapon looks and avoids a pop when the real item is hidden. Needs a capture of both before the first skill ships.
2. **Axis sign convention.** Right-hand rule with `left` as the +axis (recommended; matches JOML, MC world, and the `fly`/`spin` code today) vs a
   `right` axis (offsets to the right positive) and clockwise-positive rotation. Name-keyed offsets make the offset side a non-issue; the open point
   is rotation sign only.
3. **Sprite authoring unit**: blocks (`size`, taken here) vs vanilla scale.
4. `ribbon`/`trail` internals (segment count, the history sampler) are deliberately thin here and need a follow-up.

## References

- 関連 ADR: [[adr/0301-skill-engine-v2-frame-tree]], [[adr/0290-generic-vfx-primitives]], [[adr/0276-skill-performance-one-body-one-weapon-one-lease]] (v1 passenger transport), [[adr/0239-custom-particle-sprites-as-textdisplay-glyphs]]
- Audit: `/tmp/claude-1000/skill-engine/audit/root-causes.md`, `/tmp/claude-1000/skill-engine/semantic-diff.md`
- v1: `d41b82f1e`; stream/plate patch input: `wp/se-streak` `bea1b2641`
