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

Proposed. Board SKL-16 (rev1-3), SKL-20 (rev4). Design only; no engine code changes in this task.

**rev4 (2026-10-29, Fable, SKL-20)**: adds the **precompute contract** (section C, the centre of this ADR), generic nesting of timing groups
(2.0), the rewritten projectile op (3.6), named conditions (3.8), `lock` and `cancel` (10.6), the expansion dump format and static checks
9-12 (11.3, 11.4), the acceptance suite A1-A9 (12) and example j. Resolves every open spelling: the law window starts at `at` (never
`from`), `then_event` is gone, `bound:` has one form. The converter (6) is retired; migration is hand-written v3.

**rev4.1 (2026-10-29, Fable, after the RTM research `design/rtm-lessons.md`, Codex)**: timing is never class L, bounded or not (C5, check 9);
`until` is a mask over a finite horizon, never a bound, so `n` is never -1 (2.0); occurrence count is `ceil(for / every)` (2.0, C6); a family
and a repeater are distinct lowerings (2.0); one tick phase order and a same-tick cascade cap (10.2a); `exit.reason` so exit actions can be
written per exit cause (10.1); swept projectile collision, the hit/end order on one tick and handler sub-tables outliving the body (3.6);
JSON rows carry their YAML source path (11.3). Examples d and e corrected; r5 strengthened. The adopted / rejected RTM lessons are listed under
"Open decisions". No new founder question.

**rev4.2 (2026-10-29, Fable, wave 1 resolutions `design/wave1-resolutions.md`)**: `every` takes exactly one bound, `for` or `times` (2.0, check 11);
ribbon `history`, `style` and width are explicit fields (2.0b, 5); one instance-id rule and the `use:` default id (9); `particle_def` spelling (2.2, 8);
S / T / L classification written as a definition (3.5); motion laws hold after their window, orbit `center`, `fade` as per-tick `set`, a jump is one
duration-0 write, `steps(n)` removed (3.2, 3.4, 3.7, 4); stick window (3.6); the hit, dash and rig fields (10.1b, SKL-17); sub-table keys
`<state>.exit[<reason>]` / `<state>.on[<k>]` with no bubbling, cancel scopes (10.2, 10.6); call depth wording and recompile of callers (9, 10.4);
dump grammar additions (11.3). 2026-10-29 founder decision (F8, option B): the hit `sector` gets a required `height` (10.1b), so a sector hits only within
±height/2 of the origin's vertical position; an absent `height` is a load error (rejected `r11-sector-without-height.yaml`). Status stays Proposed.

**rev4.3 (2026-10-29, wave 2 resolutions `design/wave2-resolutions.md` R1-R41, List D)**: world ops `break_area` / `place_shape` / `explode` / `lightning`
(10.1c); hit / damage / impulse / dash rewritten, new `cleanse`, `consume_item`, `consume_status`, `freeze`, `camera_shake`, `stop_sound`, `body`; `counter`,
`caster_anim`, `place` removed (10.1b); anchor `{of, offset, sweep}`, `caster.look`, `contact` frame, frame `axes`, `flipbook`, sprite / text `see_through`
and `color`, `keys.alpha` / `keys.color`, particle `shift` (1, 1.1, 2.1, 2.2, 3.4); random streams `draw` / `roll` / `rand(name, k)` and `RandomInTiming`
(3.5); projectile `expire` (3.6); `lock.move: walk | all` (10.6); `NestedStartMissing`, `StartNamesChild`, indexed-list length, Patch path checks (10.4);
rig `of` and `reset` (10.5); dump `world` rows and event names (11.3); Patch documents, envelope keys, input events and the port list (13, 14).
**rev4.4 (2026-10-29, SKL-22 engine triage, wording only)**: records what the engine settled while the wave 1 content was loaded against it: display centring
and `#aarrggbb` colour (2.1), world op defaults (10.1c), dash `direction` required, `rand()` in a `when` guard, hit occurrence windows, check 10
counted over `[spawn, end)`, a `lock` row owning its instance, param defaults and item-part axes for frame motion, state-qualified
instance ids, ribbon opacity as the colour alpha. Each sentence names the commit that implemented it. No field is added or removed.
**rev4.5 (2026-10-29, SKL-22 gaps, wording only)**: the `seq:` desugar applies to part and machine op lists alike (11.1); a display `size` may be a T-class expression, fitted to `Key` segments (2.1, 3.5); a ribbon takes `{law: grow, prop: width}` and lowers it per tick because a trail has no client interpolation (3.4, 3.7). No field is added or removed.
Founder questions Q2, Q3, Q5, Q6 are OPEN (Q1 = B and Q4 = A resolved 10/29, Q7 = A resolved 10/29; rev4.7): every spot that depends on one carries a "pending founder Qn" note with both options and chooses neither (list under
"Open decisions"). Examples c, e, g updated; `rejected/r12`-`r14` added. Status stays Proposed (edited in place by the manager's authorisation).
- **rev4.5 (2026-10-29, SKL-22, founder 10/28 asks)**: the look lock is confirmed as the `pitch` / `yaw` fields of `lock` (10.6, no new field; the player already
- **rev4.7 (2026-10-29, founder answers Q1 = B and Q4 = A)**: `max_count` is optional on a `hit` and on an area `damage`/`status`/`heal`/`cleanse` target; omitted or `all` = unlimited, an integer >= 1 keeps the nearest N (10.1b). `damage` is optional on a `hit`: a trigger-only hit loads and its handler `damage` deals it (v2 order). The engine already decoded both spellings; the open-question switch is removed. No field is added or removed.
  reaches `lockLook` / `unlockLook`); 3.8 names the "any enemy within r" condition (`nearest_enemy_dist(caster) < r`, no new primitive) and the Paper host now serves
  `nearest_enemy_dist` / `enemy_in_cone` in guards and live cells; 3.5.1 records the CEL library policy.
**rev4.6 (2026-10-29, SKL-22 couture-rapier-s2 load; NEW OP for the founder)**: (1) a state may name an instance an EARLIER state spawned with the (5) Line ribbon player semantics: a `ribbon from:/to:` (look `shape: line`) is spawned through `ISkillVisualGateway.spawnLine(cast, from, to, thickness, colorHex, alpha)` (colour alpha is the opacity) and both endpoints are re-read from their live frames every tick through `updateLine`; it ends with `until:`/`for`. Paper draws it as one FIXED text-display strip facing the caster's eye, as v1's `line` op did (`0c9e986ef~1`: `PresentationExecutors.kt` LineExecutor, `PaperSkillVisualGateway.spawnLine`/`lineTransform`).

**rev4.7 (2026-10-29, founder Q7 = A)**: `screen_flash` and `screen_veil` are presentation ops again (10.1b rows; strict decode: overlay enum, `#RRGGBB`, veil distance 0.3..1.0 and `max_screen_fraction` up to 0.18 refused instead of coerced). `screen_flash` is a point effect through `ISkillGameplayGateway.screenFlash`; `screen_veil` is instance-lived (`spawnScreenVeil` / `updateScreenVeil` per tick, `End` at `ticks`). Sources: v1 `MechanicYaml.kt:1065-1098,1131-1163`, `PaperPlayerFeedbackGateway.kt:53-71`, `ScreenVeilHandler`. Q7 is closed; `bastion-greatsword-s2` and `-ego` carry their v2 cues again.
state-qualified spelling `/<state>/<id>` (`/throw/volley`, `/throw/fly_a.root`; 10.1 `/x` is absolute from the machine root, ids are state-qualified,
11.3). The state must be written before the referencing one, must declare the instance, and the instance must not have ended by then (a static
`End` before the spawning state's guaranteed length is a load error). A bare name never looks into another state. (2) `ribbon` takes `until:` as
well as `for:` like every extended op (5): a line ribbon ends at the event's sub-table; a trail ribbon still needs `for:`. (3) new op `held_model`
(10.1b): v1 `weapon_model` keys (`couture-s2-rig.yaml`, hidden at t20, back at t57) could not be said by `rig` (section "Skin rig").
(4) No new escape for a body that outlives its spawning state: that is the machine-level `world:` list (owner row, line 917; check 4, line 1028; check 8 bounds
it by the machine path, not a state). It is written there with `at:` for the spawn tick and named `/<start state>/<id>` from the states. This rev makes the
list complete: params are substituted in `world:` ops, skin patches may address `machine.world[<id>]` like a state's op list, and a frame / display whose
law outlasts its own `for:` writes nothing after its End (no dead Key rows).

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
**Compile produces a deterministic expansion table; the runtime only plays it** (section C, the contract every other section serves).

### C. Contract: compile = expansion table, runtime = player

Founder 2026-10-29: 「きちんとコンパイルしたらなんというか予想がつくような形、事前計算可能な状態であってほしい」「ランダム tick 後みたいなスケジュールとして
ランタイム依存にしたくない」「MythicMobs みたいな 1 tick 後、何 tick 間何 tick ごとに、その中に球を飛ばす・回転する・パーティクル」. This section is the
binding form of those three sentences. Every later section (timing 2.0, laws 3, projectile 3.6, parts 9, machine 10, expansion 11, suite 12) is a
consequence of it; where a later sentence contradicts this section, this section wins.

| clause | rule |
|---|---|
| **C1 Compile** | `YAML -> SkillDef -> ExpansionTable` happens entirely at load. The table is a pure function of (the YAML, the parts it calls, the item frames, `server config`). It is dumped by `astera skill expand <id>` (format 11.3) and is byte-identical across loads, hosts and runs. |
| **C2 Static part** | The static part of the table lists, per state, **every tick** on which anything happens, and for each: the spawns (frame, display, ribbon, particle burst, sound, projectile body, part instance), the transform keys sent (segment boundaries of section 4, with the full payload), the hit windows (shape, frames, ticks), the gameplay effects, the sub-skill / part calls, and the ends (which instance is removed). An entry is `(state, tick, op id, occurrence i, action)`. |
| **C3 Event-relative sub-tables** | Everything that happens after an event (`hit`, `bounce`, `end`, `hurt`, `input.*`, `<call>.<event>`, `emit:`) is a **sub-table relative to that event's tick**, computed at compile time exactly like the static part, with its own ticks starting at 0. The runtime only pastes it at `event tick + offset`. Nesting is unbounded in depth but statically finite (C6). |
| **C4 What may depend on runtime** | Exactly four things, and nothing else: (a) **who** is hit / targeted (the victim set, the contact entity); (b) **which** events fire, and at which tick inside their statically bounded window (a bounce at tick 7 or 9; a hurt at 0..8 of the parry window); (c) **positions**: target, contact point and normal, the caster's live pose, follow-mode anchors (class L, 3.5), each used only as a VALUE in a precomputed row; (d) **chance outcomes** of `rand()` and `chance:` fields, drawn from the seeded stream (3.5), which the table shows for the sample seed and lists as a distribution otherwise. All four change only VALUES inside rows that already exist in the table, never which rows exist or when they play. |
| **C5 Timing is never runtime** | Every `at`, `for`, `every`, `times`, `until`-window, `lasts`, law window, part `for`, state `for`, transition `after` is resolved at compile time to integers. Timing fields accept class S and T values only (3.5); a class L value on a timing field is a **load error** whether or not it declares a `bound` (rev4.1: a bound proves a range, not one schedule; RTM lesson). `rand()`, `t`/`lt` and caster stats on a timing field are load errors for the same reason (check 9). What depends on the world is written as an EVENT inside a finite horizon: a flight that ends by distance is `range:` (its `end` is an event in the window `[min, max]`), a wait is `until: <event>` over the owner's horizon, never a computed number of ticks. "Random delay" is written as a compile-time value chosen from the seeded stream in a non-timing field (for example `turn: {deg: "= rand() * 360"}`), never as a time. There is no `wait_random`, no runtime `schedule` with a computed delay, no tick-rate adaptation. |
| **C6 Bounded by construction** | Compile proves and prints: the maximum number of **instances** of every op (product of all enclosing `times` / `ceil(for/every)`, part call count, bounce `times`, `until`-masked candidates through the horizon), the maximum number of **live displays** at any tick (check 10, server cap `max_live_displays`), the maximum **repeat depth** and that no repeat is unbounded (check 11), and the min / max **path length** (11). A skill that cannot be bounded does not load; the runtime never has to defend against runaway content. |
| **C7 Runtime = player** | The runtime holds, per cast: the current state per machine level, the entry tick of each, the set of live instance handles, the seeded stream position, and the event queue. Each tick it looks up `table[state][tick - entry]` and performs the listed actions in table order; on an event it selects the sub-table and starts a cursor on it. It evaluates no timing, chooses no schedule, and allocates nothing except instance handles. Gateways (`ISkillVisualGateway`, `ISkillParticleGateway`, `ISkillSoundGateway`, `ISkillGameplayGateway`) receive exactly the payloads in the table with L values filled in. |
| **C8 Replay equality** | For the same (skill, seed, event sequence with ticks, L values), two plays produce identical gateway call sequences (the game-sim asserts this, A2/A9). The dump of 11.3 is what the game-sim diffs against; a difference between the dump and the actual calls is an engine bug, never "content". |
| **C9 Ops are rows, not code** | Gameplay ops, presentation ops, parts and machines all **lower into the same table type** (skeleton: `ExpansionTable`, `Row`, `SubTable`). A new op is a decoder (one file) plus a lowering function that emits rows; it never adds a runtime scheduler. Parts are inlined at compile (9); machines contribute state keys and sub-tables (10); laws contribute segment keys (3, 4). |

**Chance** (C4d): `damage {chance: 0.3}` / `rand()` are OUTCOME randomness: the row "damage at tick 8" exists unconditionally; whether it applies is drawn
at that tick from the seeded stream. This is allowed. `at: "= rand() * 10"` is TIMING randomness and is a load error (C5). The rule of thumb for an
author: a random number may change what a row does, never whether a row is in the table or when it plays.

**Cancel** (C4b, 10.6): `cancel` is an event; its sub-table (the exit actions of every state being left, depth first) is compiled like any other.
Cancelling therefore never produces a tick that is not in a table.

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
- Interpolating rotation is always slerp (shortest path; the client does the same). A `spin` (rate law) segment that would turn more than 170 degrees is
  split by the engine, never left to the client's shortest-path choice; a `keys` pair turning more than 170 degrees is a load error (3.3).
- One unit: blocks. Time: **server ticks** (20/s). There is no other clock, no update period, no wall-clock expiry anywhere in a skill (RTM lesson: its scheduler counted updates of a configurable period; v3 counts ticks only).

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
| `of`, `offset`, `sweep` | every `anchor:` reference may be the map `{of: <anchor>, offset: {...}, sweep: <blocks>}` instead of a bare id: `of` is the parent, `offset` is in its axes, `sweep` spaces one call per that many blocks of the anchor's travel since the last send (R-anchor; a particle or ribbon only). |
| `caster.look` | feet origin, axes = yaw (left, forward) and PITCH (forward tilts, up follows). `caster` keeps the yaw-only basis; `caster.eye` is the eye origin. |
| `contact` frame | origin = `contact.point`; up = `contact.normal` (an entity hit: world up); forward = the caster's yaw forward made orthogonal to up. Created when the event fires. |
| `axes` | on a frame: `caster | victim | world`, which parent's axes the frame's offset map reads (R23). |
| `victim.*` | `victim.root` (feet), `victim.head`, and the body anchor. **Pending founder Q2** (`victim.body` height): (A) `victim.body` = the live hitbox centre (0.9 for a standing player); (B) a fixed `victim.chest` at feet + 0.8. Not chosen. |
| document anchors | declared in `anchors:` they exist from tick 0; a later pose is a `frame` row with `mode: snapshot` (R29). |
| `mount` | `caster`: only on `follow` anchors whose chain is rooted at the caster (RC2). Objects on it become real passengers of the caster entity; their pose is `translation + left_rotation` relative to the caster, the client moves them with the player. Without `mount` a rooted object is world-teleported with `teleport_duration` = segment length. The engine re-sends a mounted object only on a segment boundary or when the caster heading drifts more than 3 degrees from the last send (never per tick). The passenger attachment height is ONE calibrated engine constant, printed by `expand`; v1 `VfxPlayer` (d41b82f1e, ADR-0276) is the reference behaviour. |

#### 1.1 Frames are spawned at runtime (first-class objects)

Founder 2026-10-28: a basis (origin + 3 axes) can be grown freely during a cast: not only the enemy and the caster, but a weapon, and one per weapon
instance when a throwing weapon exists several times. **A frame is a first-class object a skill can spawn** with `op: frame` (a state's `enter`/`do`, a transition `do`,
or a part), under the same timing block (2.0), laws (3) and easing (3.7) as anything else.

| field | meaning |
|---|---|
| `id` | name; with `times: N` it is an indexed family `id[0] .. id[N-1]` |
| `anchor` | the parent: `caster`, `victim` / `target` (an enemy), `world`, `contact`, **or another frame** (including one spawned earlier or a family member); the same value set as the table above |
| `part` | optional: instantiate a weapon part from `content/frames/<item>.yaml` (`pivot`, `tip`, `long`, `edge`, `root`, ...) for THIS frame: the named points and axes of the model become this instance's points and axes (`blade[1].tip`) Item-part axes (`long`, `edge`, `face`) resolve only on a frame with `part:`; elsewhere they are a load error. Frame and projectile motion laws read `params` defaults at decode time (rev4.4, `9f7b713a9`). |
| `offset`, `turn` | the initial pose relative to the parent (name-keyed offset, `turn` list); any value may be CEL with `i`, `n` (3.5) |
| `mode` | `follow` or `snapshot` as for anchors (1) |
| `motion` | laws on the origin and the axes: `move`, `orbit`, `spin`, `follow`, `align`, `keys` etc., each with its own window and `ease` (3.7), so an origin moves "by `move`, `ease: out_cubic`" |
| timing block | `at`, `for`/`until`, `times`; the frame is OWNED by its state and ends with it or earlier (5). A frame without an end is a load error. |

A frame has no appearance. It is an origin + a quaternion that things ride on.

- **Riders**: displays, ribbons, particles, sounds, hit shapes and sub-frames name a frame with `anchor: <id>` (or `anchor: <id>[i]` inside the same `times`) and are positioned
  relative to it; when the frame moves under its laws, riders follow. A rider outlives its frame never (it ends when its frame ends, 5).
- **Projectile**: a `projectile` body IS a frame (it has an origin and axes, `fly`/`move` laws, a block mode): `bolt` in a part's `frames_out` (9) is this frame.
  A projectile op may also be given `frame: <id>` to drive an already-spawned frame instead of creating one.
- **Indexing**: `times: N` creates N instances in one op; `i` in CEL selects the instance. A rider with the same `times` binds instance `i` to frame `id[i]`, so
  "one display, one hit capsule and one ribbon per blade" is three lines, not nine.
- **Parts**: a part (9) can declare frames in its `frames_out`, spawn its own, and a caller can pass a frame to `frames_in`.
- **Expansion** (11): the table lists, per state, every frame spawned (id, count, parent, part), its lifetime (start and end tick, owner state) and the writes of its riders.

```yaml
- id: blade                     # three blades, three frames (the S2 blades), 120 degrees apart about the throw axis
  op: frame
  times: 3
  anchor: shears
  part: blade_a                 # content/frames/couture-rapier.yaml: gives blade[i].root, .tip, .long, .edge
  turn: [{axis: long, deg: "= i * 120"}]
  for: 40
  motion: [{law: move, velocity: {forward: 1.0}, speed: 7.0, at: 4, for: 7, ease: out_cubic}]
```

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
| `at` | start: a tick (since cast start, or since the event inside a handler) or an event, with optional offset: `at: 8`, `at: input.release`, `at: "input.release+2"`. Inherited from the enclosing timeline group, overridable per op. |
| `for` | duration in ticks of the op's window; `for: cast` = until the cast ends. **Extended kinds** (display, ribbon) without `every`: the instance lives `for` ticks. **Point kinds** (particle, sound, hit, damage) with `every`: the window in which they repeat. |
| `every` | repeat period in ticks: occurrences at `at + k * every` (exact multiples, k = 0, 1, ...) inside the window. Absent = one occurrence. May be a range with an `ease` (3.7.1): `every: {from, to, ease}`, fire ticks computed statically. |
| `times` | number of occurrences (with `every`); exclusive with `for`; on a non-repeating op, `times: N` = N simultaneous instances (an indexed family, 1.1). |
| `until` | an event; ends the window (and every live instance of an extended kind) the first time it fires. |
| `lasts` | extended kinds WITH `every` only: how long each occurrence lives. Required there (no hidden default), so repeats cannot pile up. |

The shipped grammar (`load/grammar/Timing.kt`, `TimingSpec`) enforces the field relations and is the SSOT: `every` needs exactly one bound of its own, `for` or `times` (plus an optional `until` mask; both `for` and `times` is a load error), or `until` alone over the horizon;
`times` needs `every` (or is a family on a point op); `lasts` needs `every` and is only legal on an extended kind, where `every` REQUIRES it.

- Repeat index `i` (0-based) and occurrence count `n` are CEL variables (3.5). `n` = `times`, or `ceil(for / every)` (half-open: `every: 3, for: 7` fires at 0, 3, 6,
  `n = 3`), or the horizon count when only `until` bounds it (next bullet); `times` and `for` together with `every` is a load error
  (`SkillDecodeError.Invalid`: `every: write for or times, not both; the smaller one would be dead text`).
  `n` is never -1 and never unknown at compile time.
- **`until` is a mask, not a bound (rev4.1).** `every` + `until` with no `for`/`times` takes its candidate ticks from the enclosing **horizon**: the
  owning state's `for`, the enclosing group's `lasts`, the max `end` of the projectile body the op is anchored to, a part caller's window, or for `for: cast` the cast's max path (11); the tightest in scope wins.
  Every candidate through the horizon is a row in the table, printed with `mask until <event>`; at runtime the event cuts the remaining rows, and if it
  never fires every candidate plays. An `every` with no `for`/`times` and no finite horizon in scope is a load error (`UnboundedRepeat`).
- **Family or repeater, never both.** `times` without `every` = a family of N simultaneous instances (`id[i]`, 1.1); `times` with `every` = N
  occurrences in time. An op that must be both (three blades that pulse) is a group with `every` whose `do` holds the family op: the copy index is `i`,
  the occurrence index `parent.i` (2.0a). The two lowerings are distinct and the table names them (`blade[2]` vs `pulse[2]/blade[1]`).
- **`times` without `every` = same tick** (N simultaneous occurrences). **Per-index lists (R28)**: a field that varies per copy may be a list with EXACTLY `n`
  entries (`count: [2, 3, 5]` with `times: 3`); a list of the wrong length is a load error (`SkillDecodeError.Invalid`, `IndexedListLength`).
- Examples of the same grammar on different kinds: sound `{at: 4, every: 4, until: input.release}`; particle `{at: 7, every: 1, for: 7}`; display
  `{at: 7, for: 5}`; trail ribbon `{at: 4, until: input.release}`; multi-hit `{op: hit, at: 8, every: 2, times: 3}`; damage over time
  `{op: damage, at: 0, every: 10, for: 60}`. Each occurrence of a gameplay op sees its own `i`.

#### 2.0a Timing groups nest: any op under any group, any depth (T9, C1)

A **timing group** is `{id, at, every, for, times, until, lasts, do: [...]}`. It is an op like any other (`op: group` is implied by `do:`), so
it may appear wherever an op may (a state's `enter`/`do`/`exit`, a transition `do`, a projectile `on:` handler, a part body) and its `do` list may
contain any op **or another group**, to any depth (static limit `max_group_depth`, server config, default 8). This is the MythicMobs
"after 1 tick, every 5 ticks for 20 ticks, do: [shoot, spin, particles]" form, and it is the only way to write it.

| rule | meaning |
|---|---|
| Occurrence | a group fires at `at + k * every` for `k` in its bound (2.0); each firing is an **occurrence** and runs its whole `do` list with the clock reset: inside, `at: 0` is the occurrence tick. |
| Inheritance | NONE, except the occurrence end. A group's block is **not** the default of its children (rev4 reverses rev3's "shorthand" rule, which made `every` ambiguous when both levels had one). A child op's own timing is RELATIVE to the occurrence and must be written on the child if it is not the identity (`at: 0`, one firing). `lasts` on the group is the end of the OCCURRENCE: every child without a tighter end of its own is removed at `occurrence + lasts`, and this is the one thing a child takes from its group. `lasts` is REQUIRED when the group has `every` and any extended child (`display`, `ribbon`, nested group with one) has no `for` / `until` of its own (rule 5 applied to groups); it is a load error on a group without `every`. |
| `i` / `n` | per group level. Inside a group's `do`, `i` and `n` are the group's occurrence index and count; the enclosing group's are `parent.i` / `parent.n`, then `parent.parent.i` (depth is static, so the chain is checked at load). An op with its own `every` inside a group sees its OWN `i`, and the group's as `parent.i`. |
| `t` | inside an occurrence, `t` is ticks since that occurrence started; `cast.t` is the cast clock, `state.t` the state clock. |
| Instances | an instance spawned by an occurrence is owned by that occurrence; it ends at the earliest of its own `for`/`until`, the group's `lasts`, the group's `until`, and the owning state's exit (5). Ids inside a group are `<group id>[<i>]/<op id>` in the table and in `anchor:` references from siblings of the same occurrence (`anchor: ball` inside the occurrence resolves to this occurrence's `ball`). |
| Events | an event raised inside an occurrence (`emit`, a projectile `hit`) is scoped: a handler in the same occurrence sees it first (`on:` on the projectile), then it bubbles to the state (10.2) as `<group id>.<event>` with `event.i` = the occurrence index. |
| Bound | the instance count of every op is the product of the bounds of all enclosing groups and its own (C6); printed per op in the table. |
| Expansion | compile unrolls every group occurrence into static rows (11); nothing of the group survives to the runtime except the ids. |

```yaml
- id: volley                      # at tick 1, every 5 ticks, 4 occurrences (ticks 1, 6, 11, 16)
  at: 1
  every: 5
  for: 20
  lasts: 40                       # each occurrence's children are gone 40 ticks after it
  do:
    - {id: ball, op: projectile, ...}                    # this occurrence's ball; `i` = 0..3
    - {id: trail, op: particle, anchor: ball, every: 1, until: ball.end, ...}   # rides this occurrence's ball
    - {id: puff, op: particle, at: 2, every: 1, times: 3, count: "= 2 + parent.i"}  # own i = 0..2, group's as parent.i
```

Example j (7) is the full form with its expansion table.

#### 2.0b No hidden defaults

Every field is either **required** or defaults to the **identity value** (0, 1, empty, absent, or the plain vanilla default), and that default is
written in the field table. A value that differed in v1 is written EXPLICITLY by the converter, never supplied by the engine.

| field | choice | why |
|---|---|---|
| `fade.exponent` / ribbon `fade` (v1 `fade_power`) | identity default **1.0** (linear); the converter writes `exponent: 1.5` on every v1 element, because v1 defaulted to 1.5 (v2 silently changed it to 1.0, unapproved) | the schema default is the neutral value, and the v1 look is visible in the content instead of in the engine |
| `count`, `offset`, `extra` (particle) | identity 1, [0,0,0], 0 | vanilla overload arguments, written down |
| `scale`, `size` growth, `volume`, `pitch` | identity 1 (volume/pitch: vanilla sound defaults) | |
| `billboard`, `context`, `view_range`, `yaw`, `pitch` | vanilla `fixed`, `fixed` (decision), 1.0, 0, 0 | |
| `ribbon.style` (`soft | crisp | both`), one of `ribbon.width_frac` (with `root`) / `ribbon.width` (without `root`), `projectile.block.mode` | **required** (v2 hid `soft`) | no neutral value exists |
| display `line_width`, `text_opacity` (kind text) | **listed defaults** 200, 255 | the vanilla text_display values (line width 200, opacity 255 = opaque); not identities, so the schema prints them as `x-listed-default` |
| sound `category` | **listed default** `master` | vanilla `SoundCategory.MASTER` |
| `frame.mode` | **listed default** `follow` (the other value is `snapshot`) | the engine value; follow re-reads the parent at every send |
| `align.axis`, `stretch.axis`, `align.up` | **listed defaults** `forward`, `forward`, `world.up` | the engine values: the object's forward points at the target, up is kept toward the world up |
| `orbit.face` | **listed default** `keep` | the engine value: the object does not turn with the orbit |
| reactive law `step` | **listed default** 2 (ticks between re-sends, minimum 1) | the engine value; a live law is re-sent every 2 ticks |
| ribbon `step_deg` | **listed default** 30 (degrees between samples, 5..90) | the engine value |
| rig `sample_ticks` | **listed default** 2 (ticks between key samples, minimum 1) | the engine value |
| `lasts`, `for`/`until`/`times` bounds, `block.restitution`, `block.times`, `block.for` | **required** where they apply | no neutral value exists |

These listed defaults are the only non-identity defaults in the schema. Each is printed as `x-listed-default`, never as `default`, so a
reader of the generated schema sees which values the engine supplies. No v1/v2 source is recorded for the engine-valued rows; the vanilla rows cite vanilla.

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

Additions (rev4.3): `sprite` takes `flipbook: {frames: [...], every, loop}` (R23; `frames` are sprite ids, advanced every `every` ticks, `loop` true or false);
sprite and text take `see_through` and `color` (R33, an ARGB hex). `keys.alpha` / `keys.color` (3.3) write the display's colour channel with ONE `Set`
write per tick. A flat translucent quad is `{kind: sprite, sprite: quad, size: [1, 1], color: "#d9ff3b2f"}`, where `quad` is a 1x1 white glyph in `content/sprites/`.

**Sprites (`kind: sprite`) are text-display glyphs with a REAL size (no hidden x5).** A sprite file `content/sprites/<id>.yaml` declares
`px: [w, h]` and the loader derives the natural quad size `px x 0.025` blocks, plus the quad centre. The vanilla scale unit for a sprite is
**1 = natural size**. **Decision (SKL-16, recommendation taken): the author writes `size: [w, h]` in BLOCKS**, because "0.4 x 0.3 blocks"
reads the same regardless of the texture's pixel size; the loader divides by the natural size to get the vanilla `scale` and `expand` prints
both (`size [0.4, 0.3] -> scale [1.0, 0.75] of natural 0.4 x 0.4`). Growth laws on a sprite work on `size` (`grow` in blocks per tick). The
centre compensation is the explicit `pivot: center` macro (default `center`), expanded into `translation` and printed; no frame-distance factor
exists (`size_frac` is deleted). Item/block/text displays keep the plain vanilla `scale`.

**Display centring and colour (rev4.4).** Block and item displays centre on their drawn middle and `scale` is the plain vanilla scale
(`6b99a099c`, `0b76a1b71`). Text displays get the v1 centre compensation (`VfxQuad`), and their `scale` is the vanilla scale, which is the v1 size
x 5 (`6b99a099c`). A sprite is centred by the sprite font, so `pivot` accepts only `center`, and its size is `size` / natural, applied once, with the
gateway unit 1 (`a0b1d3798`). Colour keys are `#aarrggbb` everywhere, alpha first; a sprite's tint alpha sets its opacity, and the text background is
one `#aarrggbb` string (`a0b1d3798`). A ribbon's opacity is the alpha byte of its `color` (v1 trail `alpha: 0.35` is `#59......`); there is no separate
`alpha` field, and the player hands the gateway the rgb and the opacity from that one value (rev4.4).

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
| `shift` | per-copy offset vector (an offsetVec in the anchor's axes), class S; may read `i`, `n`, `parent.i`, `draw`, `roll`. A ring is `shift: {left: "= 2 * cos(i * 6.2832 / n)", forward: "= 2 * sin(i * 6.2832 / n)"}` with `times: n`; a jagged line is `shift` with `= (roll(1) - 0.5) * 0.3` jitter per copy (R10). |

Deleted: `dir`, `spread_deg`, `speed` (the 0.3 sphere), `inherit_velocity`, `seed`, "count as repeat" and the `vanilla` flag. A custom particle
definition is a separate op, `{op: particle_def, def: couture-s2-p-pink, anchor: ...}` with the same timing block, never an overload of `particle`. The server-side per-call count cap
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

Every law has its own window: `at` (ticks since the instance started, identity default 0; the spelling `from` is a load error, rev4: `from` is
reserved for RANGES such as `speed: {from, to}` and for the projectile origin), `for` (ticks; or `until: <event>`; default to the end of the
instance), `every` + `times` (a law that restarts: `spin ... every: 10, times: 3` is three windows), and `ease` (`linear` default, names and total-vs-rate semantics in 3.7). A law is an ordinary part of the timing grammar (2.0): same words, same meaning, relative to the instance. Axes are named (3.1) and RESOLVED ONCE at law start, then frozen in the parent basis (a spinning object does not wobble).
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

A law's contribution persists after its window (hold): a finished move stays displaced, a finished spin stays turned. Nothing returns to rest unless a
law brings it back. A rate law is an integral: `grow` and `fade` keep their final value; laws on one channel add (offsets), compose (rotations, list
order) or multiply (scale, alpha); `keys` is absolute in its own law and may not overlap another law on its channel (load error).

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
| `accel` (field on `spin`, `grow`) | rate of the rate: `deg_per_tick`, blocks/tick, size/tick | `extra` (speed) `accel`; `count` rate | `pitch` / `volume` rate |
| `move` / `orbit` / `follow` | `offset` | emission point `anchor`; with `count: 0` also the direction vector | source `anchor` |
| `spin` | `rotate` | the direction vector (`count: 0`) | n/a |
| `fade` | `alpha`; on a display, written every tick (no tween exists), lowered to `set text_opacity` rows | `count` to 0 | `volume` to 0 |
| `align` / `stretch` | orientation / length | direction vector | n/a |

- `move`: `dir` or `velocity`, and `speed`. It has no `accel`, `gravity` or `drag` (those are projectile fields, 3.6).
- `orbit`: `center` is a literal point `{forward, up, left}` in the object's anchor basis; the radius is the distance from the offset at law start to
  `center` (static, C1). To orbit another object, anchor on it and write `center: {forward: 0}`.
- `shift` (position, per copy): on any kind that has `times`, `shift: <offsetVec>` adds a per-copy offset in the anchor's axes. Class S; may read `i`, `n`,
  `parent.i`, `draw`, `roll` (3.5). It composes with `offset` and laws like another position term and is resolved at compile (rows in 11.3 show the final offset).
- `fade` is lowered to one `set <field>=<value>` row per tick (`text_opacity` on a text or sprite display; it folds into `count` on a particle and
  `volume` on a sound). There is no `alpha` on a `Key`: a key is a Transformation write.

Composition: laws on the same property add their contributions (rates add; rotations compose per 3.2), each only inside its own
`at`/`for` window; a `to:` target blends from the value at window start. Examples: `{law: grow, prop: count, per_tick: 0.5}` (an emit that thickens),
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
| `rand()` | seeded, deterministic uniform [0,1): seed = (cast id, op id, i, call-site order), so replays and the game-sim are identical A `rand()` in a `when` guard on a row is a runtime guard that draws once per firing, from a seed derived from (cast seed, row, tick, event index, victim) (rev4.4, `21217df3c`, `8486ac54d`). |
| `draw`, `roll`, `rand(stream, k)` | named deterministic streams (R11), all class S. `draw` / `draw(name)` is one value per (cast, name), shared by every row that reads it (a skill-wide random pick); `roll` / `roll(k)` is one value per (cast, tick, row), fresh at each occurrence; `rand(name, k)` is the k-th value of stream `name` for this cast. Replay-identical like `rand()`. |
| `event.victim`, `event.i` | inside a handler: the entity or victim that caused the event, and the event index (bounce count, pierce index). |
| `contact.*` | in `hit.damage` and `hit.on` expressions: `contact.point`, `contact.normal`, `contact.victim`. |
| `stacks(entity, status)`, `velocity(anchor)` | live-class functions (L): current stack count of a status on an entity; the anchor's velocity in world axes. |
| `forward`, `left`, `up`, `long`, `edge`, `face` | the object's axes as WORLD vectors (anchor axes first, then item part axes) |
| `caster`, `target`, `contact` | positions with `.pos`, `.forward`, `.eye`; `contact.normal`, `contact.point`; `target` is the victim or aim target |
| `row.<name>` | the current `each:` row; `${param}` for params; `anchor.<id>` for any anchor |

**Classification is a definition, not a heuristic.** An expression is T iff it reads `t`, `lt`, `cast.t` or `state.t`; L iff it calls a frame
function (`pos vel speed axis forward up left right dist dist_xz in_cone behind hp_ratio`) on `caster` / `target` / `victim` / `enemy` or an anchor of
basis L, or a world function (`aim_point look_dir nearest_enemy_dist enemy_in_cone`) or `stacks` / `velocity`; S otherwise. The world function `counter` is removed (the `counter` op is gone, 10.1b).
`draw`, `roll` and `rand(name, k)` are class S. **`RandomInTiming` (extends check 9, 10.4 / 11.4):** `rand`, `roll` or `draw` in `at`, `for`, `every`,
`times`, `until`, `lasts` or a transition `then` is a load error; in an op `when` (and in any value field) it is allowed (C4d; rejected `r12`). Snapshot versus live is syntax: an
identifier (`caster.pos`, `contact.normal`) is read ONCE at occurrence start (S, C4c); a call (`pos(caster)`) is live (L). An unknown function is a
load error (`BadExpr`), never silently S.

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

#### 3.5.1 CEL library policy (founder 10/28: "details in CEL; libraries allowed, with judgement on which kinds")

Skill details are written in CEL; a function (or a CEL extension library) may join the engine's function set only if it meets all of these:

| rule | meaning |
|---|---|
| Deterministic | the same inputs and the same seeds give the same result on every host and replay. Randomness only through the seeded `rand` / `roll` / `draw` family; no wall clock, no `random()`, no iteration order of unordered collections. |
| Side-effect free | a function reads its arguments and, for a world function, the cast's world through `ExprHost`; it never writes the world, an entity, a status, a cooldown or any state, and never does I/O. A change to the world is an op, not an expression. |
| Keeps the precomputed table | a function of constants only is class S and folds at load (3.5); a function that reads `t` / `lt` / `cast.t` / `state.t` is class T; a function that reads live state (a frame function, a world function, `stacks`) is class L and is listed in the classifier (`ExprClassifier`). A new function is added to the classifier in the same change, so an unclassified call is never silently S; an unknown function stays a `BadExpr` load error. Timing fields (`at`, `for`, `every`, `times`, `until`, `lasts`, `then`) accept class S only (C5), so no library function can make the table depend on runtime state. |
| Typed and total | the CEL type-check at load accepts the call (argument and result types declared); at run time it either returns a value of its declared type or raises `ExprEvalException`, which a guard turns into a fault plus `false`, never a silent default. A world function that cannot answer fails loudly or returns a documented neutral value (e.g. "no enemy" is a very large distance). |
| Bounded | O(arguments) or a world query with a documented reach (`nearest_enemy_dist`: 64 blocks); no unbounded loops, no allocation proportional to world size. |
| Vanilla-neutral names | functions use plain English names with units in degrees and blocks; a library function that merely renames an existing one is not added. |

Allowed kinds: maths (trig, clamp, mix, smoothstep, vector algebra), easing, string and list helpers that are pure, seeded random streams, read-only frame
and world queries. Not allowed: anything that mutates, any I/O or clock access, reflection, user-defined recursion, and any library that is not
deterministic across JVMs. A third-party CEL extension library is adopted function by function against this table, not wholesale; the first
function that fails a row blocks the library. A new function also updates `docs/contributing/add-a-skill-yaml.md`.

#### 3.6 Gameplay uses the same grammar: projectile block mode

`projectile` takes the timing block (`for` replaces `max_ticks`), spawns a gameplay FRAME (its body, 1.1) and carries its handlers inline. Every
field below is required unless marked identity; no field has a hidden default (`SpawnOps.kt`'s `maxBounces = 3` and friends are deleted).

```yaml
- id: ball
  op: projectile
  origin: muzzle                       # anchor the body starts at (rev4: `origin`, was `from`; `from` is only for ranges)
  velocity: "= forward * 1.0"          # direction, blocks/tick at launch; CEL class S
  speed: {from: 0.6, to: 1.2, for: 10, ease: in_quad}   # optional ramp (3.7.1); a plain number is a constant speed
  range: 12                            # blocks of travel after which `end` fires (or `for: N` ticks; at least one of range / for)
  radius: 0.5                          # hit capsule radius
  hit: {targets: enemies, once: true}  # once: the first victim ends the flight (`end` follows `hit`); false = pierce, max `times`
  motion:                              # laws on the body frame, same grammar as 3 (spin about forward = a spinning ball, C7)
    - {law: spin, axis: forward, deg_per_tick: 36, for: 20}
  block: {mode: bounce, restitution: 0.6, times: 3}    # mode required; other fields required per mode
  on:                                  # event-relative sub-tables (C3); each list is a `do` list, timing relative to the event
    hit:    [{use: skill:fire-splash, with: {power: 2, at_frame: contact}, frames: {origin: contact}}]
    bounce: [{op: sound, anchor: contact, sound: block.stone.hit, pitch: "= 1.0 + 0.1 * event.i"}]
    end:    [{use: skill:dust-puff, frames: {origin: ball}}]
```

| field | meaning |
|---|---|
| `origin` | the anchor whose origin and basis the body is spawned with (`muzzle`, `caster.eye`, `contact`, a frame id). |
| `velocity`, `speed` | direction and speed. `speed: {from, to, for, ease}` is the ramp of 3.7.1 (T-class, exact path at compile). `gravity`, `drag` are projectile fields (`move` has none, 3.4). |
| `range` / `for` | end of flight by distance or ticks (`range` needs a static `speed`); both allowed, the earlier end wins; neither is a load error (5). The `end` tick is listed in the table as a bounded window `[min, max]` (speed ramp and bounces make it a range). |
| `radius`, `hit` | the hit capsule and victim rule. `hit.targets`: `enemies` / `allies` / `all` / `blocks_only`; `hit.once`; `hit.times` (pierce count, required when `once: false`). Collision is **swept**: each tick the engine tests the capsule swept from the previous body position to the current one, so a fast body never tunnels and there is no substep count field (RTM's `repeat`/`maxRepeat` are not imported). The contact point is the first point of the sweep that hits. |
| `motion` | laws on the body frame (3). `spin` here is a frozen-axis rotation of the body basis; displays `anchor: ball` ride it (one segment write for a constant spin). `align: {axis: forward, to: velocity}` keeps the nose on the path through bounces (one write per bounce). |
| `block` | `mode` required: `despawn` (events `end`), `stick` (`for` ticks stuck, which extends the flight end window to `[1, for + block.for]`; events `stick`, `unstick`, `end`), `bounce` (`restitution` required, `times` required = max bounces; events `bounce` with `event.i`, `end` after the last or at `range`/`for`), `pass` (no block events; `range`/`for` end it). |
| `on` | handlers keyed by event: `hit`, `bounce`, `stick`, `unstick`, `block` (any block contact, before the mode's own event), `expire`, `end`. `expire` fires just before `end`, ONLY when the body ends naturally by `range` or `for` (not on a hit, a block mode end or cancel). Each value is a `do` list; `at: 0` = the event tick; `contact`, `target` / `event.victim`, `event.i` are bound inside. `end` fires exactly once per body, after every other event, also on cancel. On one tick the order is fixed: body motion, swept collision, then the events in the order `hit` (per victim, declaration order), `block`, the mode's event (`bounce` / `stick` / `end`), and their `@0` handler rows in that same order (10.2a). **Handler sub-tables are owned by the occurrence (the cast), not by the body**: an `on: hit` explosion keeps running after the body despawns; `contact` and the body's last pose are **snapshots** taken at the event tick (example j: `frames: {origin: ball}` in `on: end` is the last pose). A handler list may hold groups, displays, `use:` parts, `use: skill:` calls and gameplay ops; it is compiled to a sub-table (C3). |
| trail (C5) | there is **no `onTick` op**. A trail is a rider: `{op: particle, anchor: ball, every: 1, until: ball.end}` or a `ribbon` on the body frame. It is a static row set, the body position is its only L value. |
| `frame` | optional: drive an already spawned frame (1.1) instead of creating one (`times: N` on the projectile makes N bodies `ball[i]`). |

Displays anchored on the body end with `until: ball.end` (or `until: ball.unstick` for a stuck prop) and follow it through the bounce; the bounce is a
reflection of the body's `move` law, the display needs no extra law. The `end` sub-table is pasted at the actual end tick, which is runtime
(C4b) but inside the printed `[min, max]`.

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
| parametric | `cubic_bezier(x1, y1, x2, y2)` (CSS meaning, x in [0, 1]); `steps(n)` does not exist (it is n jumps: write n keys) |

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
   `elastic`, `bounce` (large `f''`) usually reach the per-tick rule.
4. The sub-segment endpoints are sampled from `E`, so the first and last of the window always match the unmodified end values exactly.

`expand` (11) prints, for each eased law, `m`, `h`, the max error and the writes, and the lint warns above one write per tick as it does for any T expression.

Properties that no client interpolates (ribbon `width`, which the adapter applies to each trail point it pushes) are not fitted to `m + 1` segments: they lower per tick, one `set` per tick where the value changes, as `fade` does in 3.4.

#### 3.7.1 Speed and spacing take the same `ease`

- **Speed**: `move {speed, for, ease}` follows the total rule (`displacement = velocity * for * E(u)`), so `ease: out_cubic` is a dash that decelerates and `in_quad` is
  an acceleration. `projectile` takes `speed: {from, to, for, ease}` for a speed ramp (a shot that accelerates in): the speed at lt is `lerp(from, to, E(lt / for))`
  and the path is the exact integral, computed at expand time (a T expression); the gameplay body and the display share it.
- **Spacing (timing)**: `every` may be a range, `every: {from: 6, to: 2, ease: in_quad}`: the period shrinks from 6 to 2 ticks across the op's `for` window. The fire ticks are computed
  STATICALLY at expand time by `t0 = at`, `t(k+1) = t(k) + max(1, round_half_up(lerp(from, to, E((t(k) - at) / for))))`, while `t(k) < at + for` (bounded by `times` if given), and listed in the table (11).
  A plain number `every: M` keeps the exact-multiples rule of 2.0 and is the same as `from == to`, `ease: linear`. Spacing ease needs a finite `for` (an `until`-only window is a load error, as for any ease).

**Particles and sounds** (fire-and-forget, 3.4): the law is sampled at each occurrence's `lt`; an eased `count`/`offset`/`extra`/`volume`/`pitch` changes the sampled value per
occurrence and needs no segments; the occurrence train IS the sampling.

#### 3.8 Named conditions (L1)

A skill or part declares pure CEL predicates once under `conditions:` and references them by name, so a guard used by five transitions and three
`when:` fields is written once and typed once.

```yaml
conditions:
  grounded:   "= caster.on_ground"
  in_range:   "= dist(caster, target) < ${range}"
  low_health: "= caster.health_ratio < 0.35"
```

| rule | meaning |
|---|---|
| Reference | `when: cond:in_range` on any op, transition or handler (the string form, the same spelling family as `use: skill:<id>`); in a CEL expression as `cond.in_range` (so `when: "= cond.grounded && !cond.low_health"`). |
| Scope | a skill's conditions are visible in its machine and in the parts it calls only through `with:` (a part sees nothing of its caller, 9); a part declares its own. An unknown `cond:` name is a load error. |
| Class | a condition's class (S/T/L, 3.5) is derived from its body; a condition on a **timing** field is illegal like any expression (C5); on a transition guard it is evaluated at the event tick (C4b). |
| Expansion | the table prints each guard by name and, for S-class, its value under the sample inputs. |

**Enemy within r (founder 10/28, "distance_less_than any enemy").** No new primitive: the condition is the CEL world function
`nearest_enemy_dist(ref)` (3.5; feet to feet, a very large value when no enemy is within 64 blocks) compared with the radius, declared once and used by name:

```yaml
conditions:
  enemy_near: "= nearest_enemy_dist(caster) < ${range}"
```

An enemy is a hittable entity that is neither the caster nor on its team; "any enemy within r" is therefore `nearest_enemy_dist(caster) < r`. The class is L
(a world read), so the condition is evaluated at the row or event tick, never folded. `enemy_in_cone(ref, range, half_angle_deg)` is the same test limited to a
horizontal fan. Both are served by the game-sim recorder and by the Paper host through the cast's entity query. `max_count` is unchanged (Q1 = B, rev4.7).

### 4. Sending to the client (RC1)

Per object the engine keeps the last sent state and sends **segments**. One send is ONE entity-metadata write containing, together,
`Transformation` (translation, left, scale, right), `interpolation_duration = d`, `start_interpolation = 0` (+ `teleport_duration = d` and the
teleport in the same tick when the location moves). **Duration and transformation are never written separately, and rotation and scale never
have different durations** (RC1 root cause). The payload is the state at `s + d`, sent at tick `s`, with the continuity invariant that the
previous segment ended at `s` exactly; no "sample at tick+1" compensation exists.

Re-send happens only at:

1. spawn: state(0) is written at creation with duration 0 (hidden until the first segment, no pop); the first segment is sent on the NEXT tick;
2. a law/key boundary: law `at`/`until`, a key time, an eased sub-segment boundary;
3. an error-budget boundary inside a law: gravity/drag arcs, pivot-offset arcs, `orbit` (budget 0.03 blocks / 2 degrees; at most 59 ticks per segment,
   the teleport_duration limit);
4. the law set changing: event, `reparent`, parameter change, `remove`;
5. reactive laws (`follow`, `align` toward a moving anchor, a mounted caster's heading drift): every `step` ticks (default 2, minimum 1); a live (class L) or high-frequency T expression (3.5) is written every tick;
6. a gameplay anchor's reactive motion: every tick.

A jump (law start value, `phase_deg`, `grow start:`) is one duration-0 write on its tick; the next segment starts the tick after. Two writes to one
object on one tick never occur (`MachineCheck(12)`). On the spawn tick the jump IS the spawn write (rule 1).

Constant-rate cases (coin 2's spinning shears, petals, streaks) therefore cost ONE write per law for the whole spin. Client latency jitter
(one tick) is accepted. A game-sim gateway that models the client (value shown at tick t = lerp(prev, target, min(1, (t - writeTick)/d)) and checks
authored-vs-shown rotation and position) becomes the shipped gate (BS1/BS2).

### 5. Lifetime (RC5)

**Every object declares its end in the timing block (2.0); an end (`for`, `until`, or `for: cast`) is REQUIRED on `display` and `ribbon`, and a
bound (`for`, `times`, or `until`) on any repeating op; a skill without it fails to load.**

```yaml
for: 24                      # n ticks after the start
until: input.release               # first time the event fires
for: cast                    # explicit: until the cast ends (cancel, death, disconnect and unload count as the end)
```

Ribbon fields (no hidden default, 2.0b):

| field | meaning |
|---|---|
| `shape` | `trail` (over the anchor history of `of`) or `line` (`from`, `to`, `thickness`) |
| `of`, `root` | the traced anchor; `root` (optional) is the anchor the trail width is measured from |
| `history` | ticks of tip history drawn; required on a trail, forbidden on a line (this is not `lasts`, which stays the occurrence life) |
| `style` | `soft`, `crisp` or `both`; required |
| `width_frac` | width as a fraction of root-to-tip; requires `root` |
| `width` | width in blocks; forbids `root` (a projectile has no root). Exactly one of `width_frac` / `width` is required |

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

### 6. Converter strategy (`tools/skill-convert`) - RETIRED in rev4

Founder 2026-10-29 (memory `project_skill_engine_v3_decisions`): the converter is retired; the kept Limbus skills (`bastion-greatsword-*` x4,
`couture-rapier-*` x5) and every basic skill are **hand-written in v3**, one migration package each (work-breakdown wave 2). The rules below are
kept as the reading guide for v1 tracks (what a run of keys means as a law) and as the spec of what the hand migration must preserve
(R6 gravity, R8 explicit `linear`, R10 particle 1:1, R12 explicit non-defaults). `tools/skill-convert` is deleted with the v2 purge; `expand`
moves to `astera skill expand` (11.3).

Input v1 vfx element tracks: per-key `offset`, `rotation` (Euler yaw/pitch/roll in the v1/0301 convention `Ry(-yaw) Rx(pitch) Rz(roll)`), `scale`.
Output laws first, keys last.

| # | rule |
|---|---|
| R1 | Unwrap each Euler channel (309, 349, 509 are continuous), convert keys to quaternions, then process per channel (rotation, offset, scale) independently. |
| R2 | Per consecutive key pair compute the segment. **Two keys are a single segment = one constant-rate law by definition.** |
| R3 | Adjacent segments merge when rate is within 1% (or 0.5 deg/tick; 0.005 blocks/tick) and, for rotation, the axis is within 1 degree. |
| R4 | Rotation run -> `spin {axis, deg_per_tick, at, for}`; offset run -> `move {velocity, at, for}`; scale run -> `grow {per_tick, at, for}`. Non-zero starting values become the object's static `rotate`/`offset`/`size`. |
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
- (j) [j-mythic-composition.yaml](0303-examples/j-mythic-composition.yaml): the MythicMobs composition (founder requirement 4): at tick 1, every
  5 ticks for 20, fire a spinning ball 12 blocks with a trail, `on: hit` and `on: end` sub-skills with params; the full static table and the
  event-relative sub-tables are in its comments.
- `rejected/` : documents that MUST fail to load, one named error each (A8): `r1-state-overrun.yaml` (a state's `for` shorter than its owned hit
  train), `r2-random-timing.yaml` (`rand()` in `at`), `r3-unbounded-repeat.yaml` (`every` without a bound), `r4-law-from.yaml` (law window spelt `from`),
  `r5-live-timing-no-bound.yaml` (L-class `for` without `bound`), `r6-hidden-default.yaml` (projectile `block: {mode: bounce}` without `times`),
  `r7-unknown-condition.yaml` (`when: cond:nope`), `r8-group-every-no-lasts.yaml` (repeating group with a display child and no `lasts`),
  `r9-every-for-and-times.yaml` (`every` with both `for` and `times`), `r10-hit-bare-damage.yaml` (`hit` with `damage: 12`, no kind),
  `r11-sector-without-height.yaml` (`hit` with `sector: {deg, range}` and no `height`), `r12-rand-in-timing.yaml` (`roll` in `every`, `RandomInTiming`),
  `r13-dash-flag-missing.yaml` (`dash` without `invulnerable`), `r14-start-names-child.yaml` (`machine.start: p/c`, `StartNamesChild`).

Sizes in the examples are illustrative (the sprite files decide them); shapes, rates and timings are taken from the current v1 files. **Every
example under `0303-examples/*.yaml` loads, expands and plays exactly to the table in its comments (A1, A2); every `rejected/*.yaml` fails with
the error named in its first comment line (A8).** An example that stops loading is a failing test, not a stale doc.

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
| custom `emit particle: <def>` | `op: particle_def, def: <def>` | n/a (engine particle) |
| `updatePose`/`updateScale` (2-tick scale, 1-tick pose) | one segment write | `interpolation_duration` |
| `HeldItem` mesh (cloned stack, THIRDPERSON_RIGHTHAND) | `item: main_hand` resolved to model id, `context: fixed` (open decision 1) | `item_display` context |
| world-teleported "attached" mesh | `mount: caster` | passenger |
| `size_frac` (x parent frame distance), glyph x5 | `size` in blocks | `scale` |
| timeline group `every` loops, separate `sound every`, `emit spacing` loops, `at` + `until` scattered | the one timing block `at / for / every / times / until / lasts` on every op | n/a |
| `fade` default 1.0 (v1 `fade_power` 1.5) | `fade.exponent` identity 1.0, converter writes 1.5 explicitly | n/a |
| `projectile {block_mode, stick_ticks, restitution, max_bounces, max_ticks, from}` | `projectile {origin, velocity, speed, range/for, radius, hit, motion, block: {mode, for, restitution, times}, on: {hit, bounce, stick, unstick, block, end}}` (3.6) | n/a (gameplay) |
| `loop {count, every_ticks, do}`, `delay {after, do}`, `schedule`, timeline group as default block | nested timing group `{at, every, for, times, until, lasts, do}` with per-level `i`/`n`, `parent.i` (2.0a) | n/a |
| `cancel` / `finishNow` from code, `lock`/`unlock` movement ops | `cancel {scope}` and `lock {move, pitch, for}` ops (10.6) | n/a |
| `when:` CEL repeated per site | `conditions: {name: "= ..."}` + `when: cond:<name>` (3.8) | n/a |
| literal-only numbers | any numeric/vector field accepts `"= CEL"` with `t`, `i`, `n`, `rand()`, axes, `caster/target/contact` | n/a |
| (new) free `wait`/`seq` sequences | `for N, every M` is primary; `seq:` is sugar that desugars to the same ops, equality asserted by lint | n/a |
| (new) eased spacing and speed ramps | `every: {from, to, ease}`, `projectile speed: {from, to, for, ease}`, `move ease`; fire ticks listed in the expansion table | n/a |
| `frames: [{id, kind, parent, model, part}]` (static, one per name) | `op: frame {id, times, anchor, part, offset, turn, motion, for}` spawned at runtime, indexed, owned by a state (1.1) | n/a |
| (new) per-law `ease` names | `ease: linear\|<in_\|out_\|in_out_><shape>\|cubic_bezier(..)`; ease shapes the accumulated total (3.7) | n/a, sent as linear segments within the budget |
| (new) L-expression timing | `{expr: "= ...", bound: [lo, hi]}` declared or load error (11); this is the ONLY spelling | n/a |
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
| `with:` shape (R37) | an op field is always its own map: `damage: {physical: 24}`. A scalar `damage: 24` is only a PART ARGUMENT (`with: {damage: 24}`) that the part body splices into its own map (`damage: {physical: "= damage"}`); a bare scalar on an op is a load error (rejected `r10`). |
| Arguments | typed; a missing required argument or a type mismatch is a load error. Any argument may be CEL (3.5); its class (S, T, L) is judged in the CALLER and fixed at the call, so `with: {window: "= 20 + i"}` is evaluated per call. There is no argument with a hidden default (2.0b). |
| Timing | a call carries the ordinary timing block (2.0): `at`, `for`, `until`, `every`, `times`. Repeating `use:` is a part call per occurrence. |
| Scope of time | **`t` inside a part is local** (0 at the part's start; `lt` is local to the current state). The caller's clock is `parent.t`, the caster's `cast.t`. `i`, `n` are the part's own occurrence index and count. |
| Frames | **a part sees ONLY the frames it was given** (`frames:`), plus `caster`, `world`, `contact`/`target` of the current event. No ambient capture of the caller's ids. This is what makes one `parry` look right on any weapon: its visuals are written against `weapon.long`, `weapon.edge`, `weapon.tip` and the bound frame supplies them. A frame binding is by reference (a follow-mode anchor stays live, a snapshot stays frozen), checked against the declared `frames_in` type. |
| Events | a part's `emits` are visible to the caller as `<call id>.<event>` (`g.parried`) in `on:` transitions (section 10). Everything the part does not emit is invisible to the caller. |
| Lifetime | **a part ends no later than its caller's `for`/`until` window and no later than its owning state.** Cancelling the caller (cancel, death, disconnect, state exit) cancels every child, depth first, running the children's exit actions and removing their displays. A child emits `end` last. |
| Depth / recursion | the static call graph (part -> part, skill -> skill) must be **acyclic**: recursion is a load error. Maximum call depth 6 (`max_part_depth`, server config; nested calls on a chain, skill -> part = 1). |
| Ids | one rule: the unroller owns every repetition and every id, and an op lowers exactly one instance whose id is its context's `self`. A `use:` without `id:` takes the callee's bare id (never `use_<idx>`); inlined rows are prefixed by the call id. A cancel / `until` name resolves among the siblings of the current occurrence, then outward through the prefix chain; `/x` is absolute from the state root; `x[2]` picks one member; an unresolved name is a load error. |
| Recompile | A part or callee change recompiles its callers; the catalog keeps the reverse index from the compiled tables. |
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
      exit:  [ ... ]                     # exit actions, run on EVERY way out; `exit.reason` (timer | event | preempt | cancel) is bound here, class S
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
| event | `input.<key>` (`use`, `attack`, `sneak`, `swap`, `release`; see 14.1), `hit` (the caster dealt damage), `hurt` (the caster is struck; was `incoming_hit`), `contact`, `block`, `bounce`, `stick`, `unstick`, `cancel`, `end`, plus any `emits` of a bound part (`<call>.<event>`), a projectile's events (`<id>.hit`, `<id>.bounce`, `<id>.end`, 3.6), a group's scoped events (`<group>.<event>` with `event.i`, 2.0a) and `emit: <name>` ops. Every event handler body is a compiled sub-table (C3). |
| entry / exit action | an ordinary op: display, sound, particle, `use`, `damage`, `status`, `impulse`, `resource`, `emit` |
| owner | every op listed under a state is OWNED by it: displays, ribbons, sounds, parts end when the state is exited (a tighter `for`/`until` of its own may end it earlier). There is no `remove` needed for ownership. A global `world:` list owns things for the whole machine. A `lock` row owns its own instance (rev4.4, `8b0116c59`), so check 4 accepts the lock's `End` without a separate spawn. |
| final | a state with `final: true`; entering it ends the machine (and emits `end` to the parent) |

**Hits, damage and resources are effects, not free-floating rows**: `damage`, `status`, `impulse`, `knockback`, `heal` and `resource` ops are valid
only in `enter`, `do`, `exit` or a transition `do`. A skill's `meta.cooldown_ticks` and `resource_cost` are the gate effects of `use` at cast start (not states).

#### 10.1b Gameplay ops used in states (carried from 0301 unchanged except for the timing block)

All take the timing block (2.0); their own fields keep the 0301 meaning. `YAML` note: the key `on` is a plain string (kaml, YAML 1.2); a YAML 1.1 loader reads it as `true`, so tools parse with a 1.2 loader.

| op | fields |
|---|---|
| `hit` | `anchor` (one frame, required; `caster` for body sectors), `shape` (one of `sector: {deg, range, height, forward}`, `sphere: <radius>`, `capsule: <radius>` with the frames' `from`/`to`; points are in the anchor frame), `targets` (`enemies` \| `allies` \| `all`, required; a FILTER over the shape's victims), `max_count` (optional: an integer >= 1 keeps the nearest N, `all` or omitted = unlimited), `ignore_invulnerability` (required, true or false), `damage: {kind: amount}` (a map; a bare number is a load error), `on: {hit, miss}` (handler lists; `miss` runs once when the firing finds no victim), `window` (required iff the hit repeats with `every`: `once` or `{every: N}`; forbidden on a single firing). A sector is a sphere wedge: points within `range` whose horizontal angle to the sector's forward is at most `deg/2`, and whose vertical offset from the origin is within ±`height/2`. `deg`, `range`, `height` are required; an absent `height` is a load error. `forward: look \| yaw` (required on a sector): `look` = the anchor's pitched forward, `yaw` = its horizontal forward. Expressions in `damage` may use `stacks(victim, status)` and `contact.*` (3.5). `damage` is optional on the hit: a hit without it is trigger-only, and a handler `damage` row (`on.hit`) deals the damage, the v2 order (founder Q4 = A, 10/29); a present `damage` map must list at least one kind. Each occurrence of a repeating hit declares its own firing window for its handler sub-table (the first to the last firing tick, never the whole state), and windows of the same event path are unioned (rev4.4, `8cbbd75f6`). |
| `damage` | `target` (`caster`, `event.victim`, `event.attacker`, or an area `{kind: enemies\|allies, around: <frame>, radius, max_count, include_self}`, all area fields required), `damage: {kind: amount}` (a map, R20; replaces `components: [{kind, amount}]`), `ignore_invulnerability?` (false = vanilla identity). No `chance` field anywhere: write `when: "= rand() < 0.3"` (class S). Area `max_count` follows the hit: optional, omitted = unlimited (Q1 = B). |
| `status` | `target`, `status`, `stacks`, `for` |
| `modify_attribute` | `target`, `attribute`, `operation`, `amount`, `for` |
| `impulse` | `target`, `vector` = an offsetVec: the frame-axes map `{of, forward, up, left}`, a world map `{x, y, z}`, or a CEL vector. Subsumes the removed `propel`. |
| `knockback`, `heal`, `resource` | as 0301 |
| `dash` | `speed`, `for`, `ease?` (3.7, total = speed x for), and the REQUIRED flags `direction`, `follow_pitch`, `stop_on_block`, `invulnerable`, `face_movement`, plus `until` (the window mask). A missing flag is a load error (rejected `r13`). `radius` and `stop_before_target` are dropped: the corridor is a sibling `hit` with `window: once` whose shape is swept along the path. Caster movement only; never `motion:`, the caster is not a display. `direction` has no default: a dash without it is a load error (rev4.4, `039242493`). |
| `reel` | `anchor`, `to`, `speed`, `ease?` (as 0301 `reel`) |
| `cleanse` | `target`, `negative_only` (true removes only harmful statuses) |
| `consume_item` | `target`, `item`, `count` (fails the cast branch via `miss` semantics of the surrounding transition guard; checked with `has_item(...)` in a `when`) |
| `consume_status` | `target`, `status` (removes the status and yields its stack count as `event.i` to the next row of the same tick) |
| `freeze` | `ticks`, `scope` (`caster` or `all`): hit-stop; the named entities' motion and animation pause for `ticks` |
| `camera_shake` | `target`, `intensity`, `for`, `direction: from_hit \| none` |
| `stop_sound` | `target`, `sound` (stops a sound started by `sound` with a long `for`) |
| `body` | `anim`, `speed`, `for \| until`: plays a caster body clip (replaces `caster_anim`; port `playBodyClip` / `stopBodyClip`, 14.2) |
| `held_model` | `model` (item model id, required), `for \| until` (required): the weapon the caster holds shows `model` for the window and its own model afterwards; two windows on one cast nest in written order. Port `setHeldModel` / `clearHeldModel` (14.2, `ISkillGameplayGateway`); Paper sets the `item_model` component of the main-hand stack and restores the remembered one. Row: `held_model model=<id>`; ended by the instance `End` like `body` (rev4.6) |
| `emit` | `name` (an event for transitions) |
| removed | `counter` (and the `counter` world function), `caster_anim` (now `body`), `place` (now `place_shape`, 10.1c) |
| `screen_flash` | `overlay` (`white\|vignette\|gold_burst\|speed_diag\|speed_radial\|impact_star`, required), `target` (default caster), `color` (`#RRGGBB`, absent = the overlay's default), `fade_in` (0), `stay` (1), `fade_out` (2) ticks. Point op, no instance: the client times the cut-in. Founder Q7 = A (2026-10-29). Fields of v1 `ScreenFlash` (`0c9e986ef~1:core/feature-gameplay/.../config/skill/MechanicYaml.kt:1065-1098`; `viewers` became `target`). Port `ISkillGameplayGateway.screenFlash` (Paper: `IPlayerFeedbackGateway.sendScreenFlash`, a title in font `astera:overlay`, v1 `PaperPlayerFeedbackGateway.kt:53-71`). Row: `screen_flash=<overlay> color=<#rrggbb\|default> in= stay= out=`. Unknown overlay or a colour not `#RRGGBB` = load error |
| `screen_veil` | `sprite` (required), `ticks` (required, static), `target` (default caster), `distance` (0.6, literal in 0.3..1.0), `scale` (1.0), `color` (`#RRGGBB`), `max_screen_fraction` (0.15, literal in (0, 0.18]). A camera-anchored quad that fades out over `ticks`; one quad per target; the instance ends at `ticks` (End row), at a state exit or at cast end, and every end removes the quads. Fields of v1 `ScreenVeil` (`MechanicYaml.kt:1131-1163`) and its `ScreenVeilHandler` (CENTER billboard, `eye + dir * distance` each tick, side clamped by `ScreenVeilMath`); v1 coerced out-of-range numbers, v3 refuses them. Port `ISkillVisualGateway.spawnScreenVeil` / `updateScreenVeil(handle, age)` (age 1..ticks, one call per tick); Paper: a TARGET-audience ACCENT-lane text display |

#### 10.1c World ops

World ops edit blocks or the world; they take the timing block and are owned by the cast, so `revert` and cancel undo them. One table row per op: the
compile emits ONE `world` row (11.3) and the gateway spreads the per-cell strikes over `ripple` ticks (`1 + k * ripple / cells`, so the work is not all on one tick).
Cancel runs `revertAll` for the cast's `Action.World` rows.

| op | fields |
|---|---|
| `break_area` | `shape` one of `sphere: {radius, ahead}`, `line: {length, width}`, `box: {width, height, length}`; `tool` (`axe` \| `pickaxe` \| `shovel` \| `none`: which blocks break and which drops apply); `block_damage` (0..2000); `ripple` (ticks to spread the cells over); `revert` (1..6000 ticks until the blocks are restored); `outside_arena` (true allows breaking beyond the arena bound) |
| `place_shape` | `shape` one of `wall: {width, height}`, `bridge: {length, width}`, `pillar: {height}`, `platform: {width, length}`; `block` (the block id placed); `consume` (an item id, or `none`); `ripple`; `revert` (1..6000); `outside_arena` |
| `explode` | `power`, `break_blocks`, `fire`: a vanilla `createExplosion` only. A damaging area is a SEPARATE `damage` op (keeps damage in the damage grammar) |
| `lightning` | `visual_only` true (effect, no damage, no fire) or false (a real strike) |

Rules: a WorldAim is axis-snapped (the look is snapped to its dominant horizontal axis, so a wall or bridge is always aligned to the grid); a shape is capped at 512 cells
(a bigger shape is a load error); a `line` steps 0.5 blocks; a `wall` is placed 2 blocks ahead, a `bridge` at feet - 1. `revert` and `ripple` are required (no hidden default).

**World op defaults and behaviour (rev4.4).** The default origin is `caster` (feet), and an offset anchor is allowed as origin (`44281da47`,
`b20508f81`). A direction is yaw-only (`b20508f81`). `height` is a valid field of `line` (`b20508f81`). The v2 defaults are `ripple` 29, `revert` 200,
`ahead` 4.0 and `outside_arena` false (`44281da47`). The per-cell `breakable(power)` gate defaults to power 3.0, and `place_shape` orders its cells
nearest to the eye (`fbeb1efdd`). `break_area` cover cells strike in six staged portions, chips skip terrain, and terrain keeps `1 + k * ripple / n`
(`5723b74f0`).

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
- **Sub-tables and levels (rev4.2): no static bubbling.** Sub-table keys are exactly `<state>.exit[<reason>]` (one per reachable reason) and `<state>.on[<k>]`
  (the `do:` effects of the k-th `on:` entry, absent when empty). The player keeps one cursor per active level and evaluates branches innermost first every
  tick; when level L transitions with reason R it pastes `D.exit[preempt]` for every active descendant D innermost first, then `L.exit[R]`, then `L.on[k]`,
  then enters the target. Ancestor timers are never placed in descendants: each level measures its own `for` / `after` from its own entry. A state's
  `parallel: [machine, ...]` regions all run; the parent emits `<parent>.end` when all regions are final.
- **`exit.reason` (rev4.1)**: inside `exit:` actions the CEL variable `exit.reason` is one of `timer`, `event`, `preempt`, `cancel`. It is class S:
  the compiler emits one exit sub-table per reason that can reach the state, so `when: "= exit.reason != 'cancel'"` on a gameplay exit action simply
  omits that row from the cancel sub-table (example e's riposte). Exit actions still run on every way out; the author chooses per action, in the YAML,
  never the engine (RTM lesson: cancel / end / abbreviate had three different cleanup paths; v3 has one path and a reason).

#### 10.2a One tick, in order (rev4.1)

Within one server tick a cast's rows play in this phase order, and the dump sorts rows by it inside a tick:

1. state rows at this tick, table order: `enter`/`exit` bookkeeping, `spawn`, `key`/`teleport`, `particle`/`sound`, gameplay (`hit`, `damage`, ...), `lock`, `call`, `emit`, `end`;
2. projectile bodies: motion, swept collision (3.6);
3. events produced this tick, delivered in 10.2 order; each event's sub-table `@0` rows play now, in the order of 1;
4. events produced by those `@0` rows (an `emit` in a handler, a `cancel`) are delivered in the same tick, at most `max_same_tick_depth` levels deep
   (server config, default 4; the compiler counts the longest `@0` chain and refuses a deeper one, check 12); deeper chains are a load error, never a deferral;
5. cleanup: instances whose `end` row played are removed, after their riders' `until` rows.

Sibling order is declaration order at every level. Two casts play in cast-start order. This is the whole runtime ordering rule; nothing is sorted by end time
or by scheduler count (RTM lesson).

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
| `repeat` / `loop` / `delay` / `schedule` | timing block `every`/`times`/`for` on an op or a nested group (2.0a); a loop over states is only legal with a counter guard `when: "= i < n"` or a timer (10.4) |
| `op: event name:` | `emit: name` as an effect; the receiver is a transition `on: {event: name}` |
| `on: {hit, block, expire, bounce, arrive}` | transitions on those events; `arrive` is `end` of the `reel` part |

#### 10.4 Static checks at load (content lint)

1. **Reachability**: every state is reachable from `start` (transitions, `then`, nested starts).
2. **Termination**: every path from `start` reaches a `final` state; a state with no outgoing transition and no `for`/`then` is an error.
3. **No unbounded loop**: any cycle in the transition graph must contain a transition guarded by a counter or `times` bound, or a state with
   `for` (a timer); otherwise the load fails.
4. **Ownership**: every `display`, `ribbon`, `sound` with `every`, looping `particle` and `use` is inside a state (or `world:`), so something owns and ends it; the existing `for`/`until` end rule (5) remains and is checked on top.
5. **Events**: every `on: {event}` names an event that exists (built-in, a declared `emits`, or an `emit:`), and every name in `emits` is emitted by the declaring machine (an `emit:` row or an inlined part of it; otherwise `MachineCheck(5)`: `emits '<e>' is declared but never emitted`).
6. **Parts**: the call graph is acyclic and its depth (nested calls, skill -> part = 1) is at most `max_part_depth` (`MachineCheck(6)` when `depth > max_part_depth`); arguments match their declared types; every `frames_in` is bound.
7. **Effects**: gameplay ops appear only where 10.1 allows; every `damage` is reachable through at least one transition (no dead damage).
8. **Rev4.3 additions** (all load errors): a `dash` missing any required flag (`SkillDecodeError.Invalid`, `r13`); a state with `states:` and no `start`
   (`NestedStartMissing`); `machine.start` naming a child `p/c` (`StartNamesChild`, R36, `r14`; `machine.start` names a top-level state only); a per-index list whose
   length is not `n` (`IndexedListLength`, 2.0); `rand`/`roll`/`draw` in a timing field or a transition `then` (`RandomInTiming`, `r12`); a Patch `path` that
   does not resolve to a row (13).

#### 10.5 Presentation stays mathematical

States do not change the visual grammar: displays use frames + laws + CEL (sections 1-3, 3.5). A state's visuals are written against bound frames
(`weapon.tip`, `body`), with `t` / `lt` as the clock; the automaton only decides WHEN a display lives and WHICH set is active.

Rig additions (rev4.3): a rig takes `of: <frame id>` (the frame the rig drives; identity: the held item, and block displays follow it) and a `reset: true`
step, a duration-0 write of the rest pose (R30; `MAX_STEP_DEG` is unchanged). `part` and `edge` are rig-step FIELDS, not events.

#### 10.6 `lock` and `cancel` (L2, L4)

**`lock`** freezes a caster input for a window. It replaces v2's paired `lock` / `unlock` movement ops (an unlock that is forgotten is a leak; here the
window ends it, 5).

```yaml
- {id: plant, op: lock, move: walk, pitch: true, at: 0, for: 12}
```

| field | meaning |
|---|---|
| `move` | `none` \| `walk` \| `all` (R32). Identity `none`. `walk` zeroes the walk speed and leaves jumping free; `all` blocks walking and jumping. Example: `{op: lock, at: 0, for: 20, move: walk}`. |
| `pitch` | `true`: the caster's look **pitch is held at its value at the op's first tick** for the window (the yaw stays free; the server re-sends the held pitch when the client drifts more than 1 degree, which is the only mechanism Paper offers). Identity `false`. A `pitch` lock makes `caster.eye` an S-class basis for the window (3.5), which is why a thrust whose hit capsule follows `caster.eye` becomes a straight line. |
| `yaw` | `true`: as `pitch`, for yaw. Identity `false`. |
| timing | `at` / `for` / `until`; `for` or `until` is required (5). At least one of `move` (not `none`) / `pitch` / `yaw` must be set (a no-op lock is a load error). Two locks overlapping on the same field are one lock until the later end. The lock ends early when its owning state exits or the cast is cancelled. |

**`cancel`** ends something early, running its exit actions. Interrupt = an ordinary transition (on `hurt`, `input.*`, a `cond:`) whose target state
runs `cancel`, so there is no separate interrupt mechanism.

```yaml
on:
  - {event: hurt, when: cond:heavy_hit, to: broken}
states:
  broken: {enter: [{op: cancel, scope: cast}], final: true}
```

| `scope` | ends | then |
|---|---|---|
| `cast` | event `cancel` to the innermost level, reason CANCEL up the chain, then `End(root)`: the whole cast, every state level, every owned instance (displays, ribbons, frames, parts, projectile bodies it spawned, locks) depth first, innermost first, each running `exit` | emits `cancel` to the cast's listeners (sub-skill callers, the parts' `end`), then `end`; the registry entry is gone the same tick |
| `state` | the innermost level exits with reason CANCEL into its `then` (a load error when the state has no `then`); its owned instances and nested machines end | `cancel` is offered to the state's `on:` first |
| `<id>` | one named instance or group occurrence family (`ball`, `volley[2]`, a `use:` call id): `End(<id>)` plus `Emit(<id>.end)`, so `until: <id>.end` riders and `on: end` handlers run; it and everything it owns (a projectile's riders, a part's displays); a projectile cancelled this way fires `end` (not `hit`) | the owner continues |

Rules: `cancel` of an id that is not live is a no-op (printed as such in the table when provable). `cancel` never skips `exit` actions (the only way to
end without exit actions is the server's `max_life_ticks` sweep). Cancel has no sub-table of its own (no `<state>.cancel[<scope>]`): it plays the exit sub-tables with reason CANCEL, in order, at offset 0, then `end` handlers. `remove: [ids]` (5) is the presentation-only early end without exit actions; `cancel` is the
general one. The static checks treat `cancel {scope: cast}` as reaching a `final` state (10.4 check 2).

### 11. Static expansion: the timeline is computed at load (compile-time timeline)

Founder 2026-10-28: behaviour should be readable and calculable at the compile stage. At load, every skill and part, with each of its states,
is expanded to a **computed timeline table** by evaluating the timing blocks (2.0), the laws' windows and every CEL expression that can be evaluated.

**The table** (per state, and per call of a part, using the expanded tree of section 9):

| column | content |
|---|---|
| frames | for each state: every frame spawned (id, count, parent, part) with start tick, end tick and owner, and the number of riders on it |
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

**Where it appears**: the `expand` CLI and lint output (`astera skill expand <id>`, format 11.3), and the skill-compare page (a table beside each skill's video, so the founder can read durations, hits and branches without playing it). It is generated, never hand-written, and a sim scenario asserts it against the engine's actual firing ticks.

**CEL classes (3.5) at expand time**

| class | expand-time treatment |
|---|---|
| S (spawn) | evaluated at expand time wherever its inputs are static (params, `i`, `n`, constants); `rand()` is evaluated with the seeded stream so the table is exactly what a cast produces for the sample seed; the table lists a range when the input is a caster-dependent value |
| T (time) | evaluated at expand time: sampled and fitted (3.5) so segment counts are exact |
| L (live) | marked `runtime-only` in the table and **must declare a bound** on numeric fields: `count: {expr: "= live_count", bound: [1, 12]}`, `amount: {expr: "= 4 * caster.level", bound: [4, 120]}`. The table uses the bound for the display cap and for min/max of values. **Never on a timing field** (C5, check 9): `for: {expr: "= dist(caster, target) / 0.8", bound: [2, 20]}` is a load error (rev4.1; it was allowed in rev4). |

**An L value outside its declared bound at runtime is clamped and logged** (the lint cannot prove it). Timing never depends on L, so min and max
path lengths are exact integers from S/T values, events' windows and horizons alone (check 8).

#### 11.1 Two notations, one result

- **Primary (recommended): declarative.** `for N, every M`: "for 24 ticks, every 4 ticks, do X". The expanded table follows by arithmetic; there is nothing to simulate, and the author sees the cadence in one line.
- **Sugar: imperative sequence** `seq: [{wait: 1}, X, {wait: 3}, Y]`, allowed in a state's `do`. It means "at the running offset, fire X; add the `wait`s". It is **only sugar**: the loader turns it into the same declarative ops (`at` per op) before anything else.

Both expand to the **identical table**. A check enforces it: the content lint (and a game-sim scenario) takes every `seq:` and asserts that the declarative form it
desugars to produces the same table as the `seq:` itself, tick for tick; a mismatch fails the load. Example h shows one effect written both ways with the same expanded table.

**Recommendation for content: the declarative form by default.** Reasons: (1) cadence and extent are readable in one place ("every 4 ticks for 24"), where the imperative form spreads them across `wait`s; (2) edits are local (change `every: 4` to `3`), where an imperative edit changes every later tick; (3) it expands by arithmetic, with no order dependence, so the lint can compare and diff tables; (4) it is what the converter emits and the laws use. Use `seq:` only for a short one-off sequence of distinct beats where each step differs (for example hit-stop, then flash, then sound) and the count is 2-4.

#### 11.2 Static check 8 and what the table also gives

8. **Bounded timing**: every `at`, `for`, `every`, `times`, `lasts` resolves to an integer at expand time (S/T evaluated), every `until` to a finite horizon (2.0). Unbounded timing is a load error.

The same table also feeds: the writes lint of 3.5, the min/max path lengths used for cooldown and animation-lock sanity checks (a skill whose
worst-case path exceeds `max_cast_ticks` fails), and the Board's per-item "durations and hits" evidence.

#### 11.3 The dump: `astera skill expand <id>` (C1, C2)

The dump is the serialised `ExpansionTable` (skeleton `design/skeleton.md`), one text block per machine level, deterministic ordering (state in
declaration order, tick ascending, op in declaration order, occurrence ascending). Example j's comments ARE this format. Grammar:

```
skill <id>  seed=<sample seed>  params={...}  bounds: instances=<N> live_displays<=<M> path=[min,max]
state <name>  for=<n|event-bounded [lo,hi]>  then=<state>  enter-from: <list>
  @<tick>  <op id>[<i>]  <action> <payload>
  ...
  branches: <event>[window lo..hi] -> <state> (guard <cond name or expr>) | timer(<n>) -> <state>
sub <event path>  (relative to <event>; window [lo,hi] in <state>)          # one block per C3 sub-table, same row grammar
sub <state>.exit[<reason>]  (relative to the exit tick)                    # one per reachable exit reason (10.2); rows differ by `exit.reason` guards
  @<tick>  ...
sub <state>.on[<k>]  (relative to the transition tick)                     # `do:` effects of the k-th `on:` entry; absent when empty
writes: <display id>: <segments> seg, max <w>/tick
```

Row `<action>` vocabulary (one per gateway call or table transition): `spawn <kind> anchor=<id> pose={...}`, `key <display> dur=<d> T={translation,
left, scale}`, `teleport <display> dur=<d> pos=<L:anchor>`, `particle <type> count= offset= extra= pos=<L:anchor>`, `sound <id> vol= pitch= pos=`,
`hit <shape> anchor= targets= max_count= ignore_invulnerability=`, `damage targets=<L:victims> amount=`, `status ...`, `impulse ...`, `world <break_area|place_shape|explode|lightning> shape= ripple= revert=` (one row per op, 10.1c), `lock move=<none|walk|all> pitch=? yaw=?`, `call <part|skill> as <call id> with={...}
frames={...}`, `emit <event>`, `end <id>`, `cancel <scope>`. An L value is printed as `<L:name bound=[lo,hi]>`; an S/T value is printed evaluated. A candidate row under an `until` carries the suffix
`mask until <a>, <b>` (the events, 2.0); a row guarded by a class L `when` on a point gameplay op carries `when <expr>`; a class S `when` that is false prints `# when false: <id>` so nothing is silent. The JSON form adds `src` (the YAML path of the op, `machine.states.cast.do[0].do[1]`) to every row for error messages and
the skill-compare page; the text form omits it so the `# expand:` blocks stay readable (rev4.1).
Instance ids are state-qualified (rev4.4, `9705845c6`, `3fb474346`): an op written in a state is the instance `<state>/<op>[i]` (a nested or part state uses its full path, e.g. `cast/volley[0]/ball`), so two states may reuse an op id.
Event names (R35, `EventPath.value`, built on the instance id text): `input.*`, `hit`, `hurt`, `contact`, `block`, `bounce`, `stick`, `unstick`, `cancel`, `end`,
`expire`, `<instance>.{hit,miss,block,bounce,contact,stick,unstick,expire,end}`, `<group>.<event>`, `<call>.<event>` (an `emit` inside a call), `emit:<name>`, `<state>.end`
(a nested machine is final). Events are offered innermost level first;
a handled event is consumed, an unhandled one bubbles to the parent the same tick. One transition per level per tick; branches in declaration order,
first match wins.

Look keys are the YAML field names, in YAML units (the player converts): sprite `sprite size scale color pivot billboard brightness glow_color
view_range see_through`; item `item part context model? rig_shading`; ribbon `shape of root history width_frac|width style color fade step_deg
lag_ticks double_sided from to thickness`. Live names (the player contract) are `<kind>:<args>`: `anchor:<instance>[.<point>]`, `follow:<anchor>`
(offset), `align:<axis>><anchor>^<up>` (rotation), `stretch:<base>><tip>` (offset, rotation, scale), `cel:<source>` (class L); `targets:` never (structured).
An unknown kind is a player fault.

`set` row: `@<tick> <id> set <field>=<value>` writes one field of a live object for one tick (the lowering of `fade`, 3.4), for example
`@12 glow set text_opacity=0.6`.

`expand --json` prints the same table as JSON (`ExpansionTableJson`, one object per row with the same fields) for the game-sim diff (A2) and
the skill-compare page.

#### 11.4 Static checks 9-12 (compile refuses what the runtime could not play, C5 / C6)

9. **No runtime timing**: a timing field (2.0 fields, law windows, state `for`, transition `after`, projectile `range`/`for`, `lasts`) that references
   `rand()`, `t`/`lt`, a caster stat, or any L-class value, bounded or not, is a load error (`SkillDecodeError.RuntimeTiming`). A `{expr, bound}` form on a
   timing field is the same error (rev4.1). Rev4.3: `rand`, `roll`, `draw` and `rand(name, k)` in a timing field or a transition `then` is `RandomInTiming`
   (3.5; allowed in an op `when`).
10. **Live display cap**: the maximum number of displays alive at one tick on any path (sum over states of owned extended instances x their bounds)
    must not exceed `max_live_displays` (server config, default 64); the table prints the peak and the tick. The peak is counted over `[spawn, end)` (rev4.4, `e337594b2`): the player queues an `End` and removes the instance in the same tick's cleanup, so a display that ends on tick T never coexists with one spawned on tick T.
11. **Bounded instances**: every op's instance bound (product of enclosing group bounds, `times`, part call count, bounce `times`) is finite and at
    most `max_instances_per_op` (default 256); an `every` carrying both `for` and `times` is a load error (2.0); a group deeper than `max_group_depth` is an error.
12. **Table closure**: every event named in `until:`, `at: <event>`, `on:` or a transition has a producer in scope (built-in for the kind, a
    projectile of this cast, a part's `emits`, an `emit:`), and every sub-table is reachable from some event producer; an unreachable sub-table or
    an unproduced event is an error (sharpens 10.4 check 5 for event-relative sub-tables). Also: the longest same-tick chain of `@0` rows that
    produce events (10.2a) is at most `max_same_tick_depth`.

### 12. Acceptance suite A1-A9 (the definition of "the engine implements this ADR")

All tests are game-sim scenarios (`tools/game-sim`, real loaders, real `SkillComposer`, in-memory gateways from `plugin:test-fixtures`) or real-content
loaders; no unit tests (coding posture). The fixtures are the ADR examples themselves, so the ADR cannot drift from the engine. Each group names
its class, what it asserts per tick, and the fixture. Migration (wave 2) starts only when A1-A9 are green.

| id | class (`tools/game-sim/src/test/kotlin/.../skillengine/`) | asserts | fixture |
|---|---|---|---|
| A1 | `AdrExamplesLoadScenarioTest#everyExampleLoadsAndExpands` | each `docs/adr/0303-examples/*.yaml` decodes with zero errors, lowers to an `ExpansionTable`, the dump is byte-equal to the `# expand:` block in the file's comments, and the dump is stable across two loads | examples a-j |
| A2 | `AdrExampleBehaviourScenarioTest#<example>PlaysItsTable` (one case per example) | playing the cast in the sim for its max path length produces exactly the gateway call sequence the table lists, tick for tick (spawn, keys, particles, sounds, hits, calls, ends); with a scripted event sequence (hit at tick k, bounce at tick m) the event sub-tables are pasted at those ticks and nowhere else (C3, C8) | examples a-j; scripted events for b, f, g, j |
| A3 | `TimingBlockScenarioTest` cases: `atForEvery`, `timesCapsOccurrences`, `untilEventEndsWindowAndInstances`, `lastsEndsEachOccurrence`, `nestedGroupsIndexAndParentIndex`, `occurrenceClockIsLocal`, `easedEveryFireTicks`, `familyTimesCreatesIndexedInstances`, `ceilCountOnNondivisibleWindow`, `untilMasksCandidatesThroughHorizon`, `everyWithoutHorizonIsLoadError`, `repeatingFamilyIsGroupPlusFamily` | per tick: the set of firing op ids and their `i`, `n`, `parent.i` values; instance start/end ticks; the eased `every` fire ticks equal the 3.7.1 formula | example d, j; inline YAML strings in the test for each case |
| A4 | `CompositionScenarioTest` cases: `partInlinedWithPrefixedIds`, `partSeesOnlyBoundFrames`, `partEmitsCrossBoundary`, `skillCallUngatedByDefault`, `skillCallGateCooldownAndCost`, `withArgsAreTypedAndRequired`, `recursionIsLoadError`, `cancelDepthFirstRunsExits` | the expanded table of the caller contains the part's rows under `<call id>/`; a part referencing a caller frame fails to load; `g.parried` reaches the caller's transition at the event tick; cost/cooldown gateway calls appear only with `gate:`; cancel order of exit actions | examples e, f, j |
| A5 | `MotionLawsWiredScenarioTest` cases: one per law (`spin`, `move`, `orbit`, `grow`, `fade`, `align`, `follow`, `stretch`, `keys`) plus `lawWindowAtNotFrom`, `overlappingLawsCompose`, `easeShapesTotal`, `segmentBudgetSplits`, `lawOnParticleAndSound` | per segment write: `(tick, duration, translation, left_rotation, scale)` equals the closed form at the segment end; rotation composed outer-to-inner; eased spin total `deg_per_tick * for`; `from` on a law = `SkillDecodeError.UnknownKey`; a particle occurrence train samples the law per occurrence | examples a, d, i; inline YAML |
| A6 | `ProjectileScenarioTest` cases: `rangeEnds`, `forEnds`, `speedRampPathIsExactIntegral`, `spinAboutForwardOneWrite`, `trailRidesBody`, `bounceHandlerCarriesIndex`, `stickUnstickEvents`, `despawnEnd`, `passNoBlockEvents`, `hitOnceEndsFlight`, `hitPierceTimes`, `onEndSubSkillWithParams`, `onHitSubSkillWithParams`, `endFiresOnCancel`, `blockModeFieldsRequired`, `sweptCollisionNoTunnel`, `hitBlockModeEventOrderOneTick`, `handlerOutlivesBodyWithSnapshotFrame` | per tick body position (world) against the integrated speed ramp within 0.001; the `end` tick inside the printed `[min, max]`; the `on:` sub-tables pasted at the event tick with `event.i`, `contact`, `target` bound; the called skill receives the `with:` values; a missing `times` on bounce = `MissingField` | examples b, f, j |
| A7 | `PartMachineWiredScenarioTest` cases: `statesForThenTimer`, `transitionOrderFirstMatch`, `eventBubblesChildToParent`, `preemptionExitsChildrenInnermostFirst`, `parallelRegionsEndWhenAllFinal`, `ownedInstancesEndOnExit`, `lockMovePitchWindow`, `cancelCastEmitsCancelThenEnd`, `cancelStateTakesThen`, `cancelInstanceFiresEnd`, `namedConditionGuard`, `machineLintChecks1to8`, `exitReasonGuardOmitsRowFromCancelSubTable`, `sameTickCascadeDepthCapIsLoadError`, `tickPhaseOrder` | the active state per tick; exit/transition/enter order of actions; display handles removed on exit; the caster pitch gateway holds the value across the lock window; event order cancel -> end; `cond:` guard values at the event tick; each of 10.4 checks 1-8 has a rejected inline YAML | examples e, g; inline YAML |
| A8 | `StrictDecodeScenarioTest#rejectedExamplesFailWithNamedError` (one case per file) + `noHiddenDefaultsInSchema` | every `0303-examples/rejected/*.yaml` fails with the `SkillDecodeError` subtype named in its first comment line; the generated JSON schema (`Fields.jsonSchema()`) contains no `default` other than identity values listed in 2.0b; unknown keys at any depth are errors | `rejected/r1..r14` |
| A9 | `ShippedV3ContentScenarioTest#everyShippedSkillLoadsExpandsAndReplays` | every `content/skills/*.yaml` and `content/skill-parts/*.yaml` loads, passes checks 1-12, dumps, and replays byte-equal to its own dump in the sim for the sample seed; the per-skill `expand` dump is stored under `build/skill-expand/` as evidence | real content |

Owner of the suite harness: package W1-20 (work-breakdown); each engine package adds its cases to the named class. A9 is the gate that wave 2
(hand-written migration) runs under: a migrated skill is "done" when it is in A9 and the founder has judged it on skill-compare.

### 13. Document envelope and Patch documents (rev4.3, R35, R38)

**Envelope keys (R38).** A skill document's top-level keys are exactly `apiVersion, kind, id, item, importance, meta, params, frames_in, emits, anchors, conditions, machine`;
any other key is `SkillDecodeError.UnknownKey`. `frames_out` is a Part-document key only. There is no `presentation` key (presentation is ordinary ops in the machine, 10.5).

**Patch documents (R35).** A variant of a skill is a document of `kind: Patch`, applied BEFORE compile to the target's decoded document:

```yaml
apiVersion: astera/v3
kind: Patch
target: couture-rapier-s2          # the skill id being patched
variant: s2-hard                   # id of the resulting variant
ops:
  - {path: "machine.states.coin1.do[hit1]", $patch: merge, value: {damage: {physical: 40}}}
  - {path: "machine.states.coin1.do[snd]",  $patch: delete}
  - {path: "machine.states.raise.do[glow]", $patch: replace, value: {id: glow, op: display, ...}}
```

`$patch` is `delete`, `replace` or `merge`. A path addresses rows by id (`do[<id>]`), not by list position, so inserting a row elsewhere does not break the patch.
A `path` that does not resolve to exactly one node is a load error (`PatchPathUnresolved`, 10.4 check 8). The patched document is then compiled like any other,
so every check of 10.4 and 11.4 applies to the variant. **Pending founder Q3** (`gameplay.expose`): (A) drop the `expose` concept entirely; (B) keep a `kind: Patch`
form for later exposure to players/config. Not chosen: the Patch grammar above is the form either option would use.

### 14. Input events and ports (rev4.3, R39, R40)

#### 14.1 Input events

`input.use | attack | sneak | swap | release`. `release` is the use key let go. The Paper mapping: `SkillChargeStarted -> input.use`, `SkillReleased -> input.release`.
Bare `charge` and `release` are NOT events (a load error, 10.4 check 5). `cancel` and `hurt` are the other caster-side events (10.1).

#### 14.2 Ports

Every port call takes `cast: CastRef(castId, caster, world)` as its first parameter, so a gateway never needs ambient state. Ports and rev4.3 additions:
`ISkillMovementGateway` (dash, impulse, lock, freeze), `ISkillItemGateway` (`consume_item`), `ISkillWorldGateway` (the 10.1c ops, `revertAll(cast)`),
`ISkillGameplayGateway` (adds `consumeStatus`, `cameraShake`, `stopSound`, `playBodyClip` / `stopBodyClip`). `currentWorld` is deleted (the world is `cast.world`).

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

Resolved (not founder decisions; engine facts, rev3/rev4):

- **Name collision `at` (rev3)**: `at` means the timing start tick ONLY; the object's parent anchor is `anchor:` everywhere (anchors, displays, particles, sounds, ribbons, parts' `frames`).
- **Law window start is `at`, never `from` (rev4, M6)**: `from` is only a range start (`speed: {from, to}`, `every: {from, to}`); the projectile origin is `origin:`. Examples a/d/e/g/i/b/f/j updated; `from` on a law or projectile is `UnknownKey`.
- **`then_event` deleted (rev4)**: the only spelling is `on: [{event: <call>.end, to}]` (example f updated). **`bound:`** has one form: `{expr: "= ...", bound: [lo, hi]}`.
- **Camera / viewer-relative frame is NOT added (rev4, R3)**: a frame that faces a viewer is per-viewer, and one cast has many viewers but ONE
  table (C1); the client's `billboard` is the only per-viewer facing, and it is a display field, not a frame. Edge-travel wording for swings
  (`spin` from the `from` edge to the `to` edge about a named axis, memory 2026-10-26) plus the `caster.eye` basis cover every authoring case
  found in the kept skills. If a case appears that needs it, it is a new ADR, not a field.
- **Group blocks do not inherit (rev4)**: a timing group's fields are its own occurrence schedule, never its children's defaults (2.0a).
- **Chance is outcome, never timing (rev4, C4d/C5)**.
- **RTM lessons (rev4.1, `design/rtm-lessons.md`)** - adopted: additive parent-relative child offsets (2.0a, already); `until` = mask over a finite
  horizon, `n` never -1; `ceil` occurrence count; family vs repeater as two lowerings; one tick phase order + same-tick cascade cap; one cancel path with
  `exit.reason`; handler sub-tables owned by the cast with snapshot frames; swept collision instead of substep counts; timing never class L; one
  server-tick clock; JSON `src` per row. Rejected: a `viewers`/receiver policy per effect (per-viewer is not table state; vanilla range rules apply; a
  future ADR if ever needed); cross-cast trigger-combo state (lives in the item / trigger layer, outside the engine); a material flipbook construct (N
  displays with staggered `at`/`for` are static rows already); mutable circle / angle state across occurrences (laws recompute from `i`, `t`);
  parameter-specialised precompiled timing variants (timing takes no parameter that is not class S at the call site; `with:` args are S or the call
  fails to load).

1. **Held-item context, RC3.** `fixed` (recommended) vs `thirdperson_righthand`. `content/frames/couture-rapier.yaml` points are measured in the
   `fixed` basis and the three pieces of coin 2 share it; `thirdperson_righthand` (`held_hand` part in the frames file) matches how the real held
   weapon looks and avoids a pop when the real item is hidden. Needs a capture of both before the first skill ships.
2. **Axis sign convention.** Right-hand rule with `left` as the +axis (recommended; matches JOML, MC world, and the `fly`/`spin` code today) vs a
   `right` axis (offsets to the right positive) and clockwise-positive rotation. Name-keyed offsets make the offset side a non-issue; the open point
   is rotation sign only.
3. **Sprite authoring unit**: blocks (`size`, taken here) vs vanilla scale.
4. `ribbon`/`trail` internals (segment count, the history sampler) are deliberately thin here and need a follow-up.

**Founder questions Q1-Q7 (rev4.3).** Q1, Q4 and Q7 are RESOLVED (10/29); the rest are pending, both options written, none chosen.

| Q | topic | option A | option B | where |
|---|---|---|---|---|
| Q1 | `max_count` on `hit` / area `damage` | required, an integer or `all` | optional, omitted = unlimited | 10.1b. **RESOLVED B (founder 10/29)** |
| Q2 | `victim.body` height | live hitbox centre (0.9 for a standing player) | fixed `victim.chest` at feet + 0.8 | 1 |
| Q3 | `gameplay.expose` | drop it | keep a `kind: Patch` form for later exposure | 13 |
| Q4 | hit `damage` optional | omit on the hit; a handler `damage` deals it (v2 order) | always required on the hit; accept the order difference | 10.1b. **RESOLVED A (founder 10/29)** |
| Q5 | couture `absorb` (example e) | transition `absorb: true` | `modify_attribute resist.physical` | example e (also option C: a `parry {for, scale: 0}` op) |
| Q6 | coin1 delay | handler `at: 1` | `hit` gets `damage_delay: 1` | example a / g |
| Q7 | screen ops | **RESOLVED 2026-10-29: A.** `screen_flash` / `screen_veil` revived as presentation ops (10.1b, rev4.7) | (was: drop them) | 10.1b |

## v1 presentation parity additions (2026-10-28)

Added so the converter can express v1 presentation without dropping it (each maps 1:1 to a vanilla Display / `spawnParticle` field):

- `block` op: `parent`, `block`, `size` (number | `[a,b,c]` | expr), `at`, `turn`, `brightness`, `times`/`every`/`life`.
- `quad` / `plate`: `brightness`, repeat (`times`/`every`/`life`, with CEL `i` / `n`); `quad` `text`, `shadow`, `see_through`.
- `emit`: repeat with `i`/`n`, `shift` (an offset in the `at` frame's axes, per copy), expression `offset`.
- `track` key: `alpha`, `color` (`#rrggbb`), `block` swap, and the full v1 ease set (31 names).
- `stop_sound` op (decoder, executor, Paper gateway); `screen_flash` / `screen_veil` done in rev4.7.
- Converter bakes (no engine field): formation / count / reverse / stagger / slot lifetime become `times` + per-copy `at`/`turn` tables and
  per-copy tracks; jitter becomes `rand('<stream>')`; oscillate and group keyframes become dense absolute track keys; ring particle
  animations become one `emit` per period tick with `times: points` and a `shift` table (random phase fixed per instance).

## Skin rig (SKL-17)

A weapon performance is one `rig` op, not a `body` clip (formerly `caster_anim`) plus a static hand point. `rig` plays a skin-rig clip (`anim`) as a tree of frames: the
body, arm and hand bones are frames carrying v1's per-tick motion; the skin displays (`spawnSkinBone`) ride them through `toDisplayWrite`; the
weapon `mesh` is a child of the animated hand frame (hand origin = arm position + arm rotation * (grip offset + pivot), rotation = arm * tilt *
wrist * rest, scale 0.9375). The real body is hidden under a `BODY` lease for the duration. The wrist `swing` keys are rotation vectors; the
runtime holds no angles in degrees or Euler triples. `tools/skill-convert` bakes v1's component-wise eased wrist keys into one rotation-vector
key per tick (`sample: true` marks the in-between ticks, which are not chunk boundaries of the rig clock). Anything the rig op cannot say (a
swapped `weapon_model`, `ghost_bodies`, block parts without the block op) is a converter error, never dropped.

The v2 tilt seen on dev (2026-10-28) came from `main_hand` hanging off the static `caster.right_hand` point at scale 1.0 while `caster_anim`
animated the arm outside the engine; v1 hung the weapon from the animated hand. Rider displays never teleport (teleport 0, interpolation N), as in v1.

The rig has no `item` field: it is the skill's `item:`, and `part?` names a part of `content/frames/<item>.yaml`. A `rig` op under `item: any`, or in a skill
with no item, is a load error (`rig needs a concrete item`).

Rig `parts` are `block` ops. v1 hangs them from the anchor pivot and turns them by the wrist swing alone (`VfxRigPlanner.partPlan`: `pivotWorld + anchorRot * R(t) * offset`, no arm), so a hand-parented rig gets a second, anchor-parented `rig` frame (`<rig>_parts`, no skin bones) that carries the parts; the weapon mesh stays on the hand frame.

## References

- 関連 ADR: [[adr/0301-skill-engine-v2-frame-tree]], [[adr/0290-generic-vfx-primitives]], [[adr/0276-skill-performance-one-body-one-weapon-one-lease]] (v1 passenger transport), [[adr/0239-custom-particle-sprites-as-textdisplay-glyphs]]
- Audit: `/tmp/claude-1000/skill-engine/audit/root-causes.md`, `/tmp/claude-1000/skill-engine/semantic-diff.md`
- v1: `d41b82f1e`; stream/plate patch input: `wp/se-streak` `bea1b2641`
