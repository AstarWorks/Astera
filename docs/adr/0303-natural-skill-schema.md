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
  - {id: hand,   at: caster.hand_r}                                  # follows the caster every send
  - {id: muzzle, at: caster.eye, offset: {forward: 0.5}, mode: snapshot}   # copied once at activation, then world-fixed
  - {id: blade,  at: shears.blade_a}                                 # part anchor; axes long/edge/face from content/frames
  - {id: flash,  at: contact, turn: [{axis: up, deg: 90}]}           # at a contact point; created when the event fires
```

| field | meaning |
|---|---|
| `at` | parent anchor: `world`, `caster` (feet, yaw-only basis), `caster.eye` (full look basis, no roll), `caster.hand_r`/`hand_l`, `<item-frame>.<part>` (axes from `content/frames/<item>.yaml`), `contact`, `victim.<root/body/head>`, or any declared anchor. A gameplay frame is a valid parent (read-only). |
| `offset` | name-keyed map in the PARENT's axes. |
| `turn` | list of `{axis, deg}` relative to the parent basis, applied in list order. |
| `mode` | `follow` (default): origin and axes re-read from the parent at every send. `snapshot`: pose copied once at activation, parent becomes `world`. |
| `mount` | `caster`: only on `follow` anchors whose chain is rooted at the caster (RC2). Objects on it become real passengers of the caster entity; their pose is `translation + left_rotation` relative to the caster, the client moves them with the player. Without `mount` a rooted object is world-teleported with `teleport_duration` = segment length. The engine re-sends a mounted object only on a segment boundary or when the caster heading drifts more than 3 degrees from the last send (never per tick). The passenger attachment height is ONE calibrated engine constant, printed by `expand`; v1 `VfxPlayer` (d41b82f1e, ADR-0276) is the reference behaviour. |

Gameplay frames stay in `frames:` (hit volumes need them). A gameplay frame moved only by closed-form laws (`fly` without drag/gravity,
`spin`, constant-rate `pose`) EXPORTS those law segments to the displays anchored on it, so a presentation on a spinning gameplay frame (coin 2
shears) is tweened by the client exactly like a presentation law. A frame moved by a reactive law (`follow`, `reel`, collision) is sampled
at its step (section 4).

### 2. Objects: thin vanilla mappings

#### 2.1 `display` (kinds `item`, `block`, `text`, `sprite`)

Every field is a vanilla field. Defaults are vanilla defaults.

```yaml
- id: shears
  op: display
  kind: item
  at: hand                         # anchor; the entity LOCATION is the anchor's world origin (+ offset)
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
  life: {ticks: 24}                # REQUIRED, section 5
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
- {id: puff,  op: particle, particle: dust, at: palm, count: 6, offset: [0.1, 0.1, 0.1], extra: 0, color: "#ff5fa2", size: 1.2}
- {id: shoot, op: particle, particle: crit, at: muzzle, count: 0, offset: "= anchor.forward", extra: 0.8}   # count 0: offset = DIRECTION, extra = SPEED
```

| field | vanilla argument |
|---|---|
| `at` | x, y, z (anchor world origin; `{anchor: bolt, sweep: 0.3}` = one call per 0.3 blocks of the anchor's travel since the last send, an explicit loop, not a particle argument) |
| `count` | count. `>0`: N particles, `offset` is the gaussian spread box. `0`: ONE particle, `offset` is the direction vector, `extra` the speed |
| `offset` | offsetX/Y/Z (world axes; CEL allowed) |
| `extra` | extra (speed; for dust etc. as vanilla uses it) |
| `color`, `size` | `Particle.DustOptions` (color, size) |
| `block`, `item` | `BlockData` / `ItemStack` data |
| `every`, `ticks` | timeline repetition (`every: 1` for a trail); not a particle argument |

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

Common fields: `from` (tick since the object exists, default 0), `ticks` or `until: <event>` (default: to the end of the object's life), `ease`
(`linear` default). Axes are named (3.1) and RESOLVED ONCE at law start, then frozen in the parent basis (a spinning object does not wobble).
Closed-form: all laws are evaluated as functions of `tau = ticks since law start`, never integrated tick by tick.

| law | fields | meaning |
|---|---|---|
| `spin` | `axis`, `deg_per_tick`, `accel?` (deg/tick^2) | `angle(tau) = rate*tau + accel*tau^2/2` about `axis` |
| `move` | `dir` or `velocity` (name-keyed or CEL), `speed`, `gravity?` (blocks/tick^2 down), `drag?` (fraction/tick) | `v' = (v + g) * (1 - drag)` per tick; closed form per segment |
| `orbit` | `center` (anchor), `axis`, `deg_per_tick`, `phase_deg?`, `face?: keep/tangent/center` | position rotates about `center` along `axis`; radius = distance at law start |
| `grow` | `per_tick` (vector or number), or `to` + `ticks` | linear change of `scale` (displays) or `size` (sprites), no exponential form |
| `fade` | `alpha_per_tick` or `to` + `ticks` | `text_opacity` for text/sprite; for item/block displays use `grow` to zero (there is no alpha) |
| `align` | `axis: <named>`, `to: velocity \| toward(<anchor>) \| <named axis/CEL vector>`, `up?` | orients the object axis; `to: velocity` of an attached `move` is constant over its segment, so it is ONE write |
| `follow` | `anchor`, `offset?`, `lag?` | position tracks another anchor; the only per-step law |
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
5. reactive laws (`follow`, `align` toward a moving anchor, a mounted caster's heading drift): every `step` ticks (default 2, minimum 1);
6. a gameplay anchor's reactive motion: every tick.

Constant-rate cases (coin 2's spinning shears, petals, streaks) therefore cost ONE write per law for the whole spin. Client latency jitter
(one tick) is accepted. A game-sim gateway that models the client (value shown at tick t = lerp(prev, target, min(1, (t - writeTick)/d)) and checks
authored-vs-shown rotation and position) becomes the shipped gate (BS1/BS2).

### 5. Lifetime (RC5)

**Every object declares its end; `life` is REQUIRED on `display`, `ribbon`, and any looping `particle`/`particle_def`; a skill without it fails to load.**

```yaml
life: {ticks: 24}            # n ticks after creation
life: {until: release}       # first time the event fires
life: cast                   # explicit: until the cast ends (cancel, death, disconnect and unload count as the end)
```

- Owner is the cast. The runtime registry is keyed by cast id; cast end/cancel/death/disconnect/unload removes everything owned. A cap
  (`max_life_ticks`, server config, default 1200) applies even to `life: cast`.
- Every spawned entity carries the PDC tag `astera:cast=<uuid>`; at boot and on chunk load a sweeper removes tagged entities with no live owner.
- `remove: [ids]` stays for early ends; it is never the only end.
- **Converter carries the v1 end (patch on wp/se-streak, `bea1b2641`, added a remove by hand because the converter read `end_tick` but
  dropped it for plates):** `life.ticks = end_tick - start_tick`; an element WITHOUT `end_tick` takes the v1 effect's `duration - start_tick`
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
| R8 | v1 `ease` stays on the law/key segment as `ease`. |
| R9 | Generated point frames (`pt_caster_0_m0p2_1`) become inline `{at, offset: {...}}` on the object; no synthetic frame ids. `like: contact` hit anchors default to the contact point (RC6). |
| R10 | v1 particle parts map 1:1 to `particle` (count, offset, extra, data, count 0 included). No default sphere, no `vanilla` flag. |
| R11 | Sprite sizes are written as `size` in blocks from the v1 plan and the sprite file's `px`; no x5, no frame-distance factor. |
| R12 | `end_tick` becomes `life` (section 5). |
| R13 | **The PROTECTED set is dropped.** Hand edits live in a `kind: Patch` document keyed by `id` (0301 composition); the converter writes only `*.gen.yaml`, so re-conversion never touches hand-written files and nothing needs to be skipped. |

Worked check (coin 1 petal 0): offset forward 1.68 -> 1.88 over 1 tick, then -> 3.52 over 4 ticks => `move 0.2/tick` for 1 tick then `move 0.41/tick`
for 4; roll 309 -> 349 -> 509 => `spin face 40` for 5 ticks (rate equal in both segments, merged by R3, start 309 static); scale 0.026 -> 0.064 -> 0.045 =>
`grow +0.038` for 1 tick then `grow -0.0048/tick` for 4. The petal goes from 12 numbers to 4 laws.

### 7. Examples

Full examples are in `docs/adr/0303-examples/`:

- (a) [a-couture-rapier-s2-coin1.yaml](0303-examples/a-couture-rapier-s2-coin1.yaml): coin 1, rewritten from `coin1.yaml`.
- (b) [b-frost-shard-projectile.yaml](0303-examples/b-frost-shard-projectile.yaml): a simple-shoot projectile skill (`frost-shard`).
- (c) [c-bastion-greatsword-s1-swing.yaml](0303-examples/c-bastion-greatsword-s1-swing.yaml): a melee sweep with a weapon swing (`bastion-greatsword-s1`).

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
| `trail {of, root, ticks, ...}` | `ribbon` over the anchor history, `life` | chain of display segments |
| `track {keys: [{offset, rotation: euler, scale}]}` | `motion: [spin/move/grow]` or `keys` with `pose` | `Transformation` segments, `interpolation_duration` |
| rotation `[yaw, pitch, roll]` | `{axis, deg}` list or quaternion | `left_rotation` |
| frame offset `[left, up, forward]` | `{forward, left, up}` map | `translation` / entity location |
| `pose {rotate: {axis, deg}}` on a display | `rotate` / `keys.pose` | `left_rotation` |
| `emit {count, dir, spread_deg, speed, inherit_velocity, seed, vanilla}` | `particle {count, offset, extra, color/size/block/item}` | `spawnParticle(count, offX, offY, offZ, extra, data)`; count 0 = direction mode |
| `emit ... spacing: s` | `particle at: {anchor, sweep: s}` | one call per sample |
| custom `emit particle: <def>` | `particle_def: <def>` | n/a (engine particle) |
| `updatePose`/`updateScale` (2-tick scale, 1-tick pose) | one segment write | `interpolation_duration` |
| `HeldItem` mesh (cloned stack, THIRDPERSON_RIGHTHAND) | `item: main_hand` resolved to model id, `context: fixed` (open decision 1) | `item_display` context |
| world-teleported "attached" mesh | `mount: caster` | passenger |
| `size_frac` (x parent frame distance), glyph x5 | `size` in blocks | `scale` |
| missing `end_tick` / `lifetime = 1_000_000` | `life` required, cast registry, sweeper | n/a |

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
- Text opacity has no native tween path (unverified); `fade` on sprites steps per tick.

### Mitigations
- The client-model gateway in game-sim and the leak gate (LeakTrackingVisuals) become mandatory for every shipped skill; `make vfx-shot` compare for
  the six reference skills per integration window.
- `astera skill expand` prints every derived vanilla value (scale from size, centre translation, passenger offset, segment table).

## Alternatives Considered

- **Keep per-tick keyframes, fix the engine only**: leaves the authoring unreadable (founder rejection), and nothing says what a rate is.
- **Euler triples with an explicit order flag**: still three coupled numbers; named axes read and compose better; Euler path differs from slerp near
  90 degrees pitch.
- **Author-written vanilla `scale` for sprites**: one fewer macro, but each sprite's natural size must be known to read it. Rejected for sprites,
  kept for item/block/text.
- **Always entity-yaw/pitch for orientation**: heading changes force per-tick teleports and give no roll; orientation stays in `left_rotation`.
- **Per-tick sends with `interpolation_duration: 1`**: simple, but every tick is a packet per object and the latency/jitter budget is spent on every
  tick (this is the 0301 behaviour).

## Open decisions (founder)

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
