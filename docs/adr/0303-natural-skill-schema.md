---
status: Accepted
date: 2026-10-28
deciders: founder, Claude
supersedes: null
partially_supersedes: 0301
superseded_by: null
---

# ADR-0303: Natural skill schema: anchors, thin vanilla objects, motion as rates

## Status

Accepted (manager decision 2026-10-30; was Proposed through rev4.20). Board SKL-16 (rev1-3), SKL-20 (rev4). The engine on `wp/se-adr0303` implements this document; where the two disagree the code wins and the ADR is edited to match (rev4.21, rev4.22).

**rev4 (2026-10-29, Fable, SKL-20)**: adds the **precompute contract** (section C, the centre of this ADR), generic nesting of timing groups
(2.0), the rewritten projectile op (3.6), named conditions (3.8), `lock` and `cancel` (10.6), the expansion dump format and static checks
9-12 (11.3, 11.4), the acceptance suite A1-A9 (12) and example j. Resolves every open spelling: the law window starts at `at` (never
`from`), `then_event` is gone, `bound:` has one form. Skills are hand-written in v3 (section 6).

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
±height/2 of the origin's vertical position; an absent `height` is a load error (rejected `r11-sector-without-height.yaml`). The ADR is Accepted since rev4.21.

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
**Founder questions Q1-Q7 (rev4.3, closed in rev4.21).** All seven are resolved (Q2, Q3, Q5 on 10/29; Q1 = B, Q4 = A, Q7 = A on 10/29; Q6 = A as an engine fact, see "Open decisions"). The "pending founder Qn" notes are gone from the body; the list under "Open decisions" keeps the decisions.
- **rev4.5 (2026-10-29, SKL-22, founder 10/28 asks)**: the look lock is confirmed as the `pitch` / `yaw` fields of `lock` (10.6, no new field; the player already
  reaches `lockLook` / `unlockLook`); 3.8 names the "any enemy within r" condition (`nearest_enemy_dist(caster) < r`, no new primitive) and the Paper host now serves
  `nearest_enemy_dist` / `enemy_in_cone` in guards and live cells; 3.5.1 records the CEL library policy.
- **rev4.7 (2026-10-29, founder answers Q1 = B and Q4 = A)**: `max_count` is optional on a `hit` and on an area `damage`/`status`/`heal`/`cleanse` target; omitted or `all` = unlimited, an integer >= 1 keeps the nearest N (10.1b). `damage` is optional on a `hit`: a trigger-only hit loads and its handler `damage` deals it (v2 order). The engine already decoded both spellings; the open-question switch is removed. No field is added or removed.

**rev4.6 (2026-10-29, SKL-22 couture-rapier-s2 load; NEW OP for the founder)**: (1) a state may name an instance an EARLIER state spawned with the state-qualified spelling `/<state>/<id>` (`/throw/volley`, `/throw/fly_a.root`; 10.1 `/x` is absolute from the machine root, ids are state-qualified, 11.3). The state must be written before the referencing one, must declare the instance, and the instance must not have ended by then (a static `End` before the spawning state's guaranteed length is a load error). A bare name never looks into another state. (2) `ribbon` takes `until:` as well as `for:` like every extended op (5): a line ribbon ends at the event's sub-table; a trail ribbon still needs `for:`. (3) new op `held_model` (10.1b): v1 `weapon_model` keys (`couture-s2-rig.yaml`, hidden at t20, back at t57) could not be said by `rig` (section "Skin rig"). (4) No new escape for a body that outlives its spawning state: that is the machine-level `world:` list (owner row, line 917; check 4, line 1028; check 8 bounds it by the machine path, not a state). It is written there with `at:` for the spawn tick and named `/<start state>/<id>` from the states. This rev makes the list complete: params are substituted in `world:` ops, skin patches may address `machine.world[<id>]` like a state's op list, and a frame / display whose law outlasts its own `for:` writes nothing after its End (no dead Key rows). (5) Line ribbon player semantics: a `ribbon from:/to:` (look `shape: line`) is spawned through `ISkillVisualGateway.spawnLine(cast, from, to, thickness, colorHex, alpha)` (colour alpha is the opacity) and both endpoints are re-read from their live frames every tick through `updateLine`; it ends with `until:`/`for`. Paper draws each segment as one `item_display` of the pack model `astera:skill_line` (two crossed double-faced planes, rev4.15; rev4.6 first drew one FIXED strip facing the caster's eye, as v1's `line` op did), and since rev4.14 as a polyline of such segments when `segments` > 1 (`spawnLine` / `updateLine` take the vertex list; section 5).


**rev4.8 (2026-10-29, founder: why does a generic engine know `blade_len`, why are hands guessed)**: (1) the CEL functions `blade_len` and `hp_ratio` are deleted from the host, the classifier, the compiler and the shared cell evaluator; a skill that calls one is a named load error (unknown function), no alias. (2) the builtin `caster.hand_r` / `caster.hand_l` / `caster.palm` (a rest-pose guess in the Paper host) are deleted; naming one is a load error. A hand is a NAMED POINT of the skill's own rig body animation: `points: {name: {bone, offset}}` in `content/animations/<anim>.yaml` (offset in pixels from the bone joint, rest axes), read per tick as `<body op id>.<point>` (a `body` op id is a frame name in CEL and an anchor parent), resolved by the one shared `CellExprs`/TablePlayer path in sim and Paper. A finished `body` keeps riding the caster at its last pose. The shipped bastion and couture body animations carry `hand_r` / `hand_l`; `couture-rapier-s2` reads `body_anim.hand_r`. (3) `anchor: {of, to, samples}` on `particle` / `particle_def` is a SEGMENT: it lowers at compile time to `samples` rows in the same tick at `lerp(of, to, k / (samples - 1))` (inclusive ends, `samples >= 2` a literal whole number, `to` and `samples` required together); `offset` and `shift` displace the whole segment; the dump prints `sample k/n` on each row. `sweep` stays the temporal spacing (particle only; still refused on `particle_def`), replacing the old `emit spacing`. (4) `grow` takes `by: f`, a factor on the held scale (ends at `start * f`), exclusive with `to` and `per_tick`; scale/size only, not with `prop: offset` or `accel`. (5) R28 per-index lists accept string entries (e.g. display `block: [gray_concrete, iron_block, black_concrete]`; the `block` field also takes `= CEL` / `${param}`); `motion` and other self-decoding lists stay outside R28 (use `= [..][int(i)]` inside a law number). (6) the per-row random seed is content-addressed: `Row.seed` (stamped by `RowSeeds.assign`, read by the one TablePlayer in sim and Paper) hashes the row's own content (kind, resolved geometry/ids, tick, copy indices), plus a document-order counter that only separates byte-identical rows; op ids and op order are not part of it, so renaming or reordering ops keeps every spread. `rand()`/`roll()`/`draw()` in ANY timing cell (`at`, `for`, `every`, `times`, `lasts`, state `for`, transition `after`) is a named `RuntimeTiming` load error.
**rev4.10 / E1 (2026-10-29, batch2 gap E)**: a `projectile` takes an optional `part:` (a part of `content/frames/<item>.yaml`, as on a `frame` op). The body takes the part's points, and `hit.shape.frames` may then be empty: the capsule `from -> to` is swept along the body's own axis, rotated by the body's live rotation, every tick. A `shape.from` / `shape.to` the part does not declare is a load error naming the part's points; a `hit.shape` with neither rider `frames` nor a `part:` is a load error.

**rev4.10 / E2 (2026-10-29, batch2 gap E)**: `anchors[].mount` is deleted (an unknown key; the frame/rig `mount` fields are unchanged) and the hidden `mode` default is gone: `mode: follow | snapshot` is required on every document anchor (every shipped anchor said `follow`, the old default). `offset` values accept a number, `${param}` or `= CEL`, like every other number cell.

**rev4.10 / F2 (2026-10-29, batch2 gap F)**: `absorb: true` on a `hurt` / `hurt.<kind>` transition cancels the incoming hit (no HP change, knockback, hurt sound or animation) and then takes the transition. Any other event, or `absorb: false`, is a load error. The table dump prints `absorb` after the guard. Port: `ISkillGameplayGateway.cancelIncomingHit(cast, attacker)`. Paper negates the hit in the incoming-damage interceptor through the pure query `CastRunner.absorbsAnyOf` (the queued `hurt` event is consumed a tick later, after the damage landed), so its gateway method is a no-op; the sim records the call.

**rev4.10 / absorbed (2026-10-29, founder decisions)**: (1) An absorbed hit is a miss for the attacker: the `hit` op or projectile contact that an `absorb: true` transition takes deals no damage, does not run `on.hit`, and counts toward no hit total (the EGO gauge and the rest of the damage pipeline never see it). Instead the attacker's op raises `absorbed` (`on.absorbed`, `event.victim` bound, the same load-time hit-scope rules as `on.hit`; valid for `hit` and `projectile`). `miss` still fires at the end of the op when nothing landed. An absorbed victim is not re-hit by the same op (`once`) and consumes a `max_count` / `hit.times` slot; an absorbed projectile contact ends the flight like any other contact (never sticks). One path in `TablePlayer` serves the sim and Paper: it asks the victim's live casts through `absorbsRun` and delivers `hurt` to them. The pre-existing Paper interceptor still handles hits that come from outside the skill engine. (2) A victim without an eye height (blocks and structures, armor stands) has `victim.center` = the geometric centre of its hitbox. `HitCandidate.eyeHeight` is `Double?` (null = eyeless); the structure placeholder 1.0 is gone and there is no hidden default. Entities with an eye height keep (feet + eye) / 2; the code models no separate "large mob" class.

**rev4.9 / G2 (2026-10-29, foundation audit P10, founder #32)**: the victim side is first class. `body` takes a required `target:` (`caster`, `event.victim`, or an attacker ref; area targets refused) and plays the clip on that player; every shipped body row now says `target: caster`. A projectile `hit` takes a required `attach: true|false`; `attach: true` + `attach_to: victim.<point>` sticks the body to the hit victim (shared `TablePlayer.stick`, same path in sim and Paper). `stick` / `unstick` are now actually fired at runtime, and `block: {mode: stick}` really stops the flight for `block.for` ticks (before, only the body state was marked and the flight kept moving). No built-in victim body point is invented (founder Q2 pending): the author names the anchor.

**rev4.9 / G6 (2026-10-29, foundation audit P17)**: the named body points of rev4.8 move out of the clips. `points:` is deleted from `content/animations/*.yaml` (a clip with `points:` is an unknown-key load error); ONE shared skeleton `content/frames/body.yaml` (`apiVersion: astera/v3`, `kind: BodyFrames`, `points: {hand_r: {bone, offset}, hand_l: ...}`, offset in pixels from the bone joint, rest axes) serves every `body` op through the port `ISkillGameplayGateway.bodySkeleton()`; the spelling `<body op id>.hand_r` is unchanged. The slim (Alex) arm stays applied by `SkinRigTransforms.point`, so there is no second table. A missing or malformed `body.yaml` is a named load error (`BodyFramesDecoder`, content-lint runtimeDecode) and `ItemFramesDecoder` skips that file.

**rev4.9 / founder decisions (2026-10-29)**: (1) `victim.center`, `caster.center` and `target.center` = (feet + eye height) / 2, read live every tick (same point on the sim and Paper gateways; `HitCandidate.eyeHeight` carries the eye height, no default); usable wherever a `victim.<point>` / `caster.<point>` anchor is accepted. (2) `gameplay.expose` removed, founder 2026-10-29 (Q3 = A; the engine never implemented it). (3) `absorb: true` on a transition = cancels the hit event (founder confirmed 2026-10-29, Q5 = A); documentation only, no behaviour change.

**rev4.9 (2026-10-29, foundation audit section 4, label and wording only)**: the v2 conversion residue is removed. The converter rules (section 6) and every converter reference are gone; section 6 now only says that content is hand-written. The v1 presentation parity additions section and the Skin rig `tools/skill-convert` sentences are deleted. The fade default note (2.0b) no longer mentions a converter, and the 0301 short easing names are removed from the text. `ItemFrames` documents are `apiVersion: astera/v3`; the decoder accepts that value only. No field is added or removed.

**rev4.10 / A-B-D3-D4-F1-F4 (2026-10-29, gap batch 2, decided gaps)**: (A1-A4, BUG) a rider of a frame family (`times: N`) now binds each copy's own member (`blade[i]` and a bare `blade` inside a family of the same size), and a rider of a MOVING frame keeps its own offset / rotate / scale: the chain's base pose is composed into the rider's spawn pose and `Key` rows (`PoseCompose`); a reactive row (follow / align / stretch) is left as authored, and a live (non-foldable) rotation or scale base on a moving chain is a named load error. A bare family name outside a family of that size is a load error. Imprecision kept on purpose: with a non-identity base on BOTH frame and rider the two offsets are added in the parent space (the inner offset is not rotated by the outer rotation). Static frames appear in the dump and the oracle (`frame` rows). (B1, GRAMMAR) a per-index list under `times: N` whose entries are each a whole value of the field AND whose own type also reads the list is ambiguous and a load error naming `per_index`; `per_index: {field: [...]}` forces the per-copy reading. A display `scale` also takes the map `{left, up, forward}` (all three). Display `rotate` / `offset` / `scale` cells that are `= CEL` of constants and `i` fold per copy. (B2) `particle_def` takes `count`. (B3) dust `size` takes a param. (B4) `grow` with `prop: offset` takes a partial axis map in `to`; a bare number there is an error. (B5) `every` with `times` stays a load error and now names `lasts`. (D3) `keys` `pose` angles fold params and `i`. (D4) the ribbon `width` law without a window names `for`. (F1) the builtin frame names (`caster`, `victim`, `contact`, `world`, ...) plus `event`, `self`, `i`, `n`, `t`, `lt`, `parent` are reserved as op ids, `anchors[].id` and part ids (state names and event names are not reserved: `cast` is ubiquitous). Shipped content renamed `aim` -> `aim_pt`, `contact` -> `hit_pt`, the unused `self` anchor removed. (F4) side-loaded YAML (`ItemFrames`, `BodyFrames`, `SkillEngineContents`) uses the same strict YAML 1.2 reader as skill files (`StrictYaml`), so `on` / `off` / `yes` stay strings. (G1, stretch) the `stretch` law's live cell is `stretch:<base>><tip>^<axis>` and the shared `TablePlayer` resolves it (so game-sim and Paper answer it identically, from the same caster / victim / document-anchor points they already serve): offset = the base point, rotation = the object axis pointing base to tip (up toward world up; a zero length holds the last rotation), scale = the anchor distance along the axis and 1 on the others; builtin (`caster`, `caster.eye`) and skill-declared anchors both work. (G2, max_count) every target `max_count` and `hit.max_count` accepts `"${param}"`, folded at load from the param default (a number >= 1 or the string `all`; an undeclared param or a default below 1 is a named load error). (G3, follow) the `follow` law on a skill-declared anchor is covered by a full replay (`MotionLawsWiredScenarioTest`).

**rev4.10 / C1 (2026-10-29, gap batch 2)**: CEL gains the constant `pi` (the uppercase `PI` stays), degree trig `sind` `cosd` `tand` `asind` `acosd` `atan2d` (degrees in / out), `mod(a, b)` = `a - b * floor(a / b)` (double, sign of `b`; `mod(-1, 3) = 2`) and `fract(x)`, all pure (class S when their arguments are). `%` stays integer-only (CEL); the compile error for `%` on a double adds the hint `'%' is integer-only in CEL; write mod(a, b) (or int(a) % int(b))`. `i`, `n` and `parent.i` stay doubles.
**rev4.10 / C2**: `let:` local bindings. A map of a skill, part, state or op may carry `let: {name: <number | bool | ${param} | "= expr">}`; a use of `name` inside any `= ` string below that map is replaced, token-wise (identifier boundaries, never after a `.`, never before `(`, never inside a quoted string), by the bound text in parentheses before anything is compiled, so lets have no runtime existence and the dump prints the substituted text. A let may read earlier lets of its map and any outer let. A name must be a plain identifier and must not be a param, a builtin (`i` reads `'i' is a builtin copy index and cannot be rebound`), an expression function, an op / anchor / frame id or an outer let. The class of a field is judged after substitution: a let of class L, T or rand used in a timing field is the usual C5 error and names the let (`display 'petal' at: '= start_tick' uses let 'start_tick' = (dist(caster, target) / 0.8), which is only known at run time (C5)`). Implemented as one raw-tree pass (`load/LetBindings.kt`) run at the top of the skill and part decoders.
**rev4.10 / C3**: check 15 (hit scope): a CEL cell or guard that reads `victim`, `event.victim` or `event.attacker` must sit in a hit / hurt handler (`<op>.hit`, `.hurt`, `.stick`, `.unstick`, or a `<state>.on[k]` whose event is `hit` / `hurt`); in a state list, a world row or any other sub-table it is a load error naming the row and its state section (`... 'victim' is only bound inside a hit handler (on: {hit: [...]}) or a transition on a hurt event; this row is in state 'windup' do: list`). `hit` damage amounts are per victim and exempt; `event.victim` / `event.attacker` are also legal in a state entered by a hit / hurt transition (its `riposte` reads `event.attacker`); `contact.*` is not checked (it is valid in bounce / stick / end tables). `target: attacker` is a named error: `target 'attacker' is spelled event.attacker (the entity that hit the caster; valid only in a hurt handler)`.

**rev4.10 / batch D laws (2026-10-29, gap batch 2 D1, D2, D5)**: (D1) a frame's `motion:` is probed at decode as copy `i = 0` of `n = times` (before: `n = 1`), so `deg_per_tick: = 10 * (i + 1)` on a `times:` frame family loads and lowers per copy exactly like a display's; the law-number error now states the one rule: a law number folds at load: a literal, a numeric `${param}` with a default, or a `= CEL` of constants, params and the copy index (`i`, `n`, `parent.i`). (D2) `drag: f` (`0 < f < 1`, a law number) on `spin` and `move`: the rate is multiplied by `1 - f` each tick, closed form over the window `offset(tau) = start + velocity * (1 - (1 - f)^tau) / f` and `angle(tau) = deg_per_tick * (1 - (1 - f)^tau) / f`; it is not piecewise linear, so it lowers like an eased law (sampled, within the segment error budget). `for` stays required; `drag` excludes `ease` and `accel` (named load error "mutually exclusive on one law"); out-of-range `f` is a load error. (D5) `align` toward a named direction or anchor accepts `ease` as a blend: with `ease` the weight `w = E(tau / for)` blends from the rotation the instance held when the window began to the aligned one (slerp); the live cell is written every tick of the window as `align:<axis>><to>^<up>@<w>` and the plain cell from the last tick on (held at `w = 1`). `ease` on an `align` toward an anchor needs `for` and refuses `step`; without `ease` it is the snap it was. `align: {to: velocity}` keeps its own `ease` blend.

**rev4.10 / G (2026-10-29, gap batch 2, skill-local fragments)**: a skill document takes a top-level `fragments:` map; a fragment is `{params?, frames_in?, do}` (a flat op list, no `machine`), read as a part with one final state and the part id `<skill>#<name>`. `{op: use, use: fragment:<name>, with: {...}, at: ...}` calls it from ANY op list: state `do:`, group `do:`, `on.hit` / `on.miss` / transition `do:` (the rows join the handler sub-table at the same tick base and see `victim`, `contact`, `event.*` as the handler does), or another fragment; the mechanism is the op-level part call, so params, `with:` typing and `frames:` binding are the part rules. Load errors: `use 'fragment:burst': this skill declares no fragment 'burst' (fragments: ring, spark)`; `fragment 'burst' calls itself (burst -> ring -> burst)`; `machine:` inside a fragment; a `skill:` callee that declares fragments. `SameTickDepth` now also counts the tick-0 events of a handler table no branch points at, so a part or fragment call inside `on.hit` no longer faults the player with a cascade error.

**rev4.12 / detach and tail (2026-10-29, design-tier-and-detach 2.3)**: a presentation row (`display`, `frame`, `ribbon`, `particle`, `particle_def`, `sound`, `held_model`, `camera_shake`, `screen_*`, `rig`) takes `detach: true`, and a `use: fragment:` / group / part call with `detach: true` marks every row it lowers. A detached row is not ended by the state exit: its owner stays the cast, so it plays all its ticks and the dump prints ` detached from=<state>`. `machine.tail: N` (ticks) is how long the run lingers after the cast ends; it is required when any row is detached and an error otherwise. The cast end (locks, cooldown, next input, `g cast-end`) does not move: only the detached visuals run until `cast end + tail`, and a new cast may start during the tail while the earlier visuals keep playing. Bound (check 8): a detached state row must satisfy `last <= max + tail`, a detached handler row `sub.window.max + tick <= owner max + tail`, else `TailOverrun(op=..., lastTick=..., run=...): a detached row ends after cast.end + tail (tail=T, needs tail >= N)`. Only presentation rows may be detached (`detach: '<src>' is gameplay (<name>)`), a rider of a detached frame must be detached, world rows are already owned by the cast, and `Call` / `emit` rows of a fragment are exempt. Check 8 is now strict for handler sub-tables too: a presentation row of an `on.hit` / `on.miss` / `on.block` / `on.end` handler that reaches the owner state's exit tick is a load error (name `detach: true`); gameplay rows of a handler stay legal on the last tick. The 23 shipped handler sound / particle rows that were silently dropped (ember-rod-spark, fireball-cast, frost-shard, glacier-spike, hand-crossbow-venom, harpoon-throw, lightning-bolt, repeater-barrage / -bolt, revolver-shot, shock-wand-zap, shortbow-arrow / -snare, spear-charge, sword-slash t1-t4, couture-rapier-s2 and its crimson patch) are `detach: true` with `tail: 2`. The oracle prints a non-gameplay `r @<cast end + tail> run-end tail=N` line, so no `g` line changes. Rig / frame point names are validated at load: `<rig or frame>.<point>` that the part's `points` do not declare is a load error naming the valid points (`anchor 'rig.root' has no point 'root' (the valid points of 'rig' are: grip, pivot, tip)`).

**rev4.10 / H2 (2026-10-29, gap batch 2, founder 10/29: "AIが困惑しないほうが良い")**: the rotation sign keeps the right-hand rule and changes only its wording. The clock-direction words (a rotation described by a viewing side) are removed from this ADR, the field docs, the load errors and the author docs, because they need a viewpoint ("looking from where?"). The convention is one cyclic sentence (section 0, "Rotation sign"): positive `deg` carries the basis axes **forward -> left -> up -> forward**. Vanilla `yaw` is never written in a skill file: a display `yaw:` is an UnknownKey load error naming `rotate` (`yaw = -deg about up` is applied by the engine only). Three worked examples and the reverse check are in section 0.

**rev4.10 / frames_in (2026-10-29, callee frames)**: a `frames_in` name of a called skill or skill-local fragment is a frame of the callee's static scope, usable wherever a point or anchor is named (particle / sound `anchor`, `to`, `shift` axes, `damage` / `status` area `target.around`, a `use` `frames:` value). A skill compiled on its own (content-lint) declares its `frames_in` names in that scope, so `anchor: spot` loads; at a call site the name means the frame the caller bound (`frames: {spot: contact}`), spelled by the caller's frame in every table cell (`AnchorNames.host`), so sim and Paper resolve `around: origin` to the caller's `contact` snapshot through the same lowered row. A `frames_in` the call does not bind stays a load error naming it (`MissingField ... frames: spot`). The sim `GatewayRecorder` refuses a `main_hand` mesh (offline caster, empty hand) and a skin bone (offline caster) exactly like `PaperSkillVisualGateway`. Cases: `CalleeFramesInScenarioTest`.

**rev4.11 / 9.4 chain targeting (2026-10-29, design-tier-and-detach Design 3)**: a `use: fragment:<name>` call written directly in an `on.hit` list takes `chain: {hops, every, pick: {kind, around: from, radius, nearest: 1, exclude: chain}}`. It unrolls at load into copies `jump[1..hops]` (call id `<call id>[k]`), copy k fires at `+k*every` after the handler's tick base, and a `pick` row (nearest enemy / ally / all around the previous victim, never one already in the chain) runs first on each copy. The fragment sees the chain bindings `victim` (this hop's pick), `from` (the previous victim; the event victim for hop 1) and the class-S constants `hop` (1-based) and `hops`; `from` is a reserved builtin frame name. `hops` folds at load (a literal, a numeric `${param}` with a default, or `= CEL` of constants and params) and is a whole number 1..16; `every` is a whole number >= 1; `radius` is a literal. WHEN each hop fires is therefore fixed at load; WHO it hits is decided at runtime. A copy whose pick finds nobody is skipped, and so are all later copies of that chain (the table keeps the rows; they just do not run). The excluded set is the event victim plus the picks of earlier copies, so a victim is never hit twice by one chain. The chaining body must be cast-owned (`machine.world` hit op); a body in a state is cut by the existing overrun check (`op 'cast/bolt.hit/jump[3]' reaches tick 9 but state 'cast' lasts 8`). Pick rows sort before the copy's other rows of the same tick (a `PICK` phase). Load errors, all prefixed `chain '<id>': ` unless noted: `hops folds at load: a literal, a numeric ${param} with a default, or = CEL of constants and params (got "= rand() * 4")`; `hops must be a whole number 1..16 (got 40)`; `every must be >= 1 tick (got 0)`; `pick.around must be 'from' (the previous victim); 'heart' is an anchor`; `pick.nearest must be 1; use max_count without chain: for an area`; `pick.exclude must be 'chain'`; `chain: excludes times:, every:, lasts: on the same call`; `fragment 'arc' takes chain binding names victim, from, hop, hops; it declares a param 'hop' (rename it)`; and, unprefixed, `chain: only a use: fragment: call inside an on.hit list takes chain: (found on 'cast/burst', op display)`. The table dump prints `pick chain=... hop=k kind=... around=from r=... nearest=1 exclude="chain"` and the call's `with={"hop":k,"hops":n}`. Cases: `ChainTargetingScenarioTest`.

**rev4.10 / timer params (2026-10-29)**: a machine timer takes `"${param}"` like every other tick field: a state's `for`, a transition's `after` and a `seq` `{wait: "${p}"}` (the running offset becomes `= c + ${p}`, folded with the params). One rule (`TickFields`): a non-negative integer, a CEL string, or `"${param}"` folded at load from the param's value (declared default, part call argument, or a patched default) BEFORE the machine is expanded, so the expansion table holds only integers. After folding, an undeclared param, a negative, a non-integer or a non-numeric value is a load error naming the field (`machine.states.<s>.for`, `...on[i].after`). `after` is now an integer like `for` (a fractional literal was accepted before). Op timing cells (`at`, `for`, `every`, `times`, `lasts`, sound `every`/`times`, law `for`/`step`) already folded `${param}` through the same param substitution; their conversion to ticks still rounds a fractional expression, unchanged. A state `for` / `after` may also be CEL over params (`"= fly + 1"`): once the param values are in, a constant expression folds to its number (`TickFields.fold`), one needing a runtime value stays a CEL string and is refused as a non-constant timer. An id field (`sound`, `particle_def.def`, any `Types.id`) takes `"${param}"` of a string param with a default, folded at load (an undeclared, default-less or non-string param is a load error naming the field; the catalog check stays in content-lint, which resolves the document's own param default). CEL `==` / `!=` promote an int literal against a double (`tier == 2`), like `<` / `+` already did. Hit shape sizes take `${param}`; world-op sizes also take `= CEL` of constants (se-world-ops). Cases: `TimerParamScenarioTest`, `ParamFoldScenarioTest`.

**rev4.12 (2026-10-29, thrown grenades: bounce, roll, fixed fuse)**: the projectile `block: {mode: bounce}` grammar now has physics, one path in `TablePlayer` for game-sim and Paper. `block.friction` (0..1, required with `bounce`, refused otherwise) joins `restitution` (0..1) and `times`. On a block contact the velocity (the last tick's displacement) is reflected: normal component `-vn * restitution`, tangent `* (1 - friction)`; a new flight (same `gravity`, `drag`) is rebased at the contact point. A bounce counts while `bounces < times` and the rebound speed is at least 0.05 blocks/tick: it fires `on.block` then `on.bounce` (`event.i` = the bounce ordinal). Otherwise the body rolls (no normal component, friction only, no events). A bounce body never ends by contact, only at `for`/`range`. `hit.reflect: true` (bounce only; needs `once: true`, `attach: false`, `targets != blocks_only`) makes a victim bounce the body off instead of ending the flight (one rebound per tick, `on.hit` still runs, `hit.times` is not consumed). `block.fused: true` (stick only, excludes `block.for`, `on.unstick` is a load error) keeps the body stuck until the flight end: no unstick, no expire. Fixed end: with `bounce` or `fused` and no victim able to end the flight, the `end` window collapses to `[for, for]`, so an explosion in `on.end` lands on a tick known at load. A skill bounds its own cast with a state `for: "= fuse_ticks"` + `then:` (a `<op>.end` transition would swallow `on.end`); the four `*-throw` grenades use `fuse_ticks` (frag 60, sticky 60, flash 50, smoke 50, the v2 lifetimes) and keep their damage and radii. The block sweep now runs from the previous tick position (`prevLocal`), not the degenerate `localAt(now-1)`. Sim hook: `GatewayRecorder.blockRay`. Cases: `GrenadeFuseScenarioTest`.

**rev4.13 (2026-10-30, integrate10-14; code already on main, no new decision)**: this entry makes the ADR match the engine after the se-held-empty, se-rig-attach, se-dead-status, se-gun-tracer and se-callee-lines merges. (1) *Held item.* A main-hand mesh (`display item: main_hand`, the held `rig` mesh) draws the model of the item the cast was STARTED with, never the live main hand: `CastRequest.heldItem` (the triggering `sourceItemId`, else the skill's own `item:` when it is not `any`) flows through `CastRef.heldItem` to the visual gateway, and `DisplayKind.AnchorMainHand` became `DisplayKind.AnchorHeldItem(itemId)`. An empty or swapped hand after the cast started changes nothing; `display item: main_hand` under `item: any` (or no item) is a `DecodeAbort`. (2) *Rig points.* The held rig's points (`rig.tip`, `rig.root`, `rig.grip`) carry `PLAYER_SCALE` (0.9375) like the mesh they belong to (a rig with `of:` is not scaled: the driven frame owns the pose), and the body clip follows the rig's hit-stop windows: `SkinRigHold(at, ticks)` stands the skin-rig clip clock still where the rig freezes (`ISkinRigAnimator.play(..., holds)`, `ISkillGameplayGateway.playBodyClip(..., holds)`). (3) *Dead victim.* `ISkillGameplayGateway.damage` returns `true` when it killed the victim; the player remembers those victims per cast and skips every later `status` op on them, and the trace records the skip (`status_skip <victim> dead_victim` in the oracle dump). (4) *Lines.* A `ribbon` `shape: line` is two flat quads crossed at 90 degrees about the from-to axis (`LineGeometry.planes`, shared by the sim recorder and `PaperSkillVisualGateway`), so it reads from every angle; and a projectile `on.expire` now binds `contact` = the body position at the end of the flight (normal up). (5) *Callee frames.* A `frames_in` name is spelled by the caller's frame through the one resolver `AnchorNames.host` in EVERY op field that names a point: ribbon `from` / `to` / `of` / `root`, projectile `origin`, hit `anchor`, besides the particle / sound / area / display / frame cells that already did. Cases: `HeldRigEmptyHandScenarioTest`, `RigAttachScenarioTest`, `GunTracerScenarioTest`, `CalleeFramesInScenarioTest`; `status_skip` has no scenario of its own yet. The rev4.6 sentence "Paper draws it as one FIXED text-display strip" is superseded by the crossed pair (end of section 2).

**rev4.14 (2026-10-30, integrate15 + wp/se-chain-w3; the code is the source, no new founder decision)**: the founder's rule (10/29: "the schedule alone must be predictable") holds for every sentence below: the end of the cast and the end of every display are load-fixed ticks; only offsets and victims are drawn at play time. (1) *Line geometry (rule L, `c5bc8db96`).* A line ribbon quad is a one-line text display whose background is `Component.text(" ")`; that box is `SOLID_BOX_PX_X` = 2.34 px wide (fitted from a real client capture, not the 5 px a space advances), so one block of length is `SOLID_SCALE_X = 1 / (2.34 * 0.025)` (about 17.09) scale units; the height is 10 px = 0.25 block, `SOLID_SCALE_Y` = 4 per block. The old constant 8 drew a 4.0 block line 0.42-0.45 of its length (`SkillDisplay.kt`, section 2). (2) *Gun muzzle (`0fa37220d`).* Guns fire their tracer from the hand, not from the look axis: the muzzle (or `flash`) anchor is `caster.eye` plus `{forward, left: -0.35, up: -0.65}` (`left` negative = the right hand), `forward` 0.45 on the rifle, pistol, revolver and smg, 0.55 on the sniper, 0.75 on the shotgun; `snapshot` on the rifle, pistol and revolver, `follow` on the smg, sniper and shotgun flash. The projectile `origin` stays on the look axis (`start` / `muzzle` at `{forward: 0.5}`), so the shot still hits where the crosshair is; only the tracer line (`gun-tracer` `frames_in: muzzle`) and the flash start at the hand (section 9). (3) *Rule D: handler gameplay rows drain after the owner exits (`483053cd5`, `RowGameplay.kt`).* A non-detached gameplay row of a handler (`hit`, `damage`, `status`, a world op, `emit`, `cancel`, `call`, a projectile body, a `pick`, an `effect` that is not presentation; `lock` is excepted, the state still owns its window) belongs to the CAST, not to the state: when the owner state (or the machine, a `final`) ends before the row's load tick, the row still plays at its load tick (`Row.drainsAfterExit`). The cast ends, its locks, drivers and state-owned instances are released, and the run stays alive until the last drained row has played; the `machine.tail` of detached visuals starts after it. A presentation row of the same handler is cut with its owner, never silently: the trace records `cut <row> <state> <reason>`, and the oracle dump says how many rows drain on the cast-end line: `g @T cast-end drain=N until=U`. A cancel cuts the drain together with everything else, and so do the caster's death, quit and world change (`CastRunner.cancelAllOf`) and match end (`cancelAll`): both reach a draining or lingering run as well as a live one (the runner keeps its owner until the run is finalised), `CancelScope.Cast` sets the cutting flag, the run is finalised at once, and the trace records `cut <row> <state> CANCEL` for every gameplay or detached row it still held. Only an author-chosen optional exit (`input.*`) leaves the gameplay rows draining. (4) *Check 8b, `HandlerCertainExit`.* A non-detached presentation row of a body's handler that plays AFTER the handler's firing tick, while the same body makes the owner's exit certain on that tick (`hit` with `once: true` and neither `reflect` nor `attach_to`; `block` in `despawn` / `stick` / `bounce` mode; `expire`), would be cut every time. That is a load error (`'<row>' plays N ticks after <handler>, but state '<s>' leaves on <event>`), never a quiet drop: the author marks the row `detach: true` (and gives `machine.tail` the ticks it needs) or moves the transition. A gameplay row passes: it drains. An optional exit (an author-chosen `input.*` event) is not certain: the presentation row is cut with a `cut` trace line and the gameplay row keeps draining. Check 8 itself no longer bounds a drained gameplay row by its state's length; the cast-length bound (next point) covers it. (5) *Cast length at load (D4, `ad2fe2ff6`).* `bounds.path.max` is a longest-path search over the state graph: a state's own length (its duration, or its last non-detached row), then its `then:` at that length or an event branch at the latest tick the event may fire; a back edge into a state already on the path is not followed. A drained gameplay row adds `entry(owner) + window.max + row.tick`. So a skill whose hit sends `throw` into a longer `stuck` is the longer cast (`couture-rapier-s2` is 122, not 94), and no golden row lands after its load-time end (`ShippedV3ContentScenarioTest#lateRows`). (6) *Chain state in tails.* `SubCursor.withRows` keeps the `ChainState` (the per-chain picks) when the tail or a cut rebuilds a firing's cursor, so a later row of a chain hop still knows its pick; a chain hop's `damage` is placed at the victim's feet (`at=` is the victim position, not the first victim's `contact`). (7) *Oracle hit poses (`2bec63ff8`).* Besides the injected-event poses (`## pose yaw=0 pitch=0`, `yaw=37 pitch=-20`; two dummies ahead), every castable skill is recorded at two hit poses, `## pose-hit yaw=0` and `yaw=37` (pitch 0, no injected event): four dummies stand at (forward, right) = (2.5, 0), (3, 2), (4.5, 3), (5.5, 4.5) blocks, so the first hit query finds a victim, every `on.hit` handler runs from a real hit and a chain skill hops 3 times. (8) *Polyline line ribbons (W2, `9983db096`, branch `wp/se-chain-w3`).* A `shape: line` ribbon takes `segments` (a literal whole number 1..24; absent = 1, the straight thread of rev4.13) and `jitter: {spread, along?, every?, follow?}`; the thread becomes a polyline of `segments` pieces, each piece the crossed quad pair of rev4.13 (section 5). Ports: `ISkillVisualGateway.spawnLine(cast, vertices, thickness, colorHex, alpha)` and `updateLine(handle, vertices)` take the vertex list (at least 2, the same count on every update) instead of `from` / `to`. (9) *Check 10 by overlap (`82cc67df1`, `wp/se-chain-w3`).* The live-display peak is the max over ticks of the displays alive at that tick, from load-fixed intervals (section 11.4); a line ribbon weighs `2 * segments` displays (`483053cd5`). Cases: `LinePolylineScenarioTest`, `ChainLightningBeamScenarioTest` (both on `wp/se-chain-w3`), `TablePlayerRulesScenarioTest` (rule D), `StaticChecksScenarioTest` (8, 8b, 10), `GunTracerScenarioTest#casterSeesTheLineOffTheLookAxis`, `ShippedV3ContentScenarioTest`. The rev4.6 text and section 5's line paragraph are rewritten in place to the crossed-quad contract.

**rev4.15 (2026-10-30, wp/se-line-item; founder principle: fix the root layer; visual only, no gameplay change)**: a `shape: line` ribbon segment is one `item_display` instead of two TextDisplay background quads, because a text background culls its back face (each quad was visible from one side only and the thread became a hairline from some angles) and four quads per segment would break the 64-display cap. (1) *Pack model.* The resource-pack builder emits `assets/astera/items/skill_line.json` (a `minecraft:select` on `custom_model_data` string 0, cases `a01`..`a15`, `tints: [{type: custom_model_data, index: 0, default: -1}]`), 15 models `astera/models/item/skill_line/aNN.json` (two crossed planes, each with opposite faces, `tintindex 0`, `shade: false`) and 15 white 16x16 textures `textures/items/skill_line/aNN.png` whose alpha is `round(255 * n / 15)`. (2) *Colour.* The server sets `ITEM_MODEL astera:skill_line` and `CUSTOM_MODEL_DATA` with the colour (RGB of the authored `#aarrggbb`, alpha first as everywhere in skills) and the alpha step string `aNN` (`round(alpha * 15)` clamped 1..15). (3) *Shader effect `skill-line`* (ADR-0305): marker `light-uv 192:240` (`Brightness(12, 15)`), item vertex patch draws the plane flat and fullbright, so the two crossed planes read as one uniform thread. (4) *Matrix.* `[dir*length | u*thickness | w*thickness]`, `itemDisplayTransform: NONE`; `LineGeometry.frame` replaces `LineGeometry.planes`; the oracle prints `frames=[along|across|normal],...` instead of `quads=`. (5) *Cost.* `StaticChecks.displayWeight` of a line is `segments` (was `2 * segments`), so a 24-segment line weighs 24. The rev4.6/4.13/4.14 line sentences above are rewritten in place to this contract. Cases: `LinePolylineScenarioTest`, `GunTracerScenarioTest`, `StaticChecksScenarioTest` (10), `ShippedSkillLinePackTest`, `ShippedV3ContentScenarioTest` (goldens).

**rev4.16 (2026-10-30, `8855b073a` + `04f1ce91a`, wp/se-skill-engine; visual only, no new founder decision)**: rendered display scale = the instance's own base scale composed with the reactive / law scale (`PoseCompose` folds the base into reactive rows). Before this, a live `align` / `follow` key reset the rider's literal scale to 1 (the volley-demo fins `0.06 x 0.06 x 0.9` became 1-block cubes 1.25 blocks from the eye). The base scale of a sprite is `size x factor` (ring / halo 1.25, `flight_fan` 0.15625). Four skills whose aligned sprites carried the old reset were re-authored so the rendered scale is unchanged: frost-nova, regrowth, sanctuary-ward, couture-rapier-s2. Parity evidence (`/tmp/claude-1000/skill-engine/nearcam-scale-parity.txt`): pos / rot / scale of every block / item / sprite / text display per pose, tick and instance, vs `079262164`: max delta 0, 0 mismatches (> 1e-5) over 6 / 16 / 188 / 2088 / 2088 states. The gameplay g-diff is empty. `CasterEyeClutter.checkDisplays` fails a skill whose block / item display coverage (2 largest scales / distance^2, view cone) stays > 1.5 for 4+ ticks. A `line` segment keeps its own matrix (rev4.15), so its thickness is not affected; the oracle diff of this branch shows only line display rows.

**rev4.17 (2026-10-30, `214e4de94`, wp/se-harpoon; gameplay op, harpoon-throw / grapple-shot / scythe-pull)**: new gameplay op `pull {target, direction, distance, lift = 0.3}` (point timing). A fixed-strength knockback flew a near victim past the caster, so `pull` launches the target so that it comes to rest `distance` horizontal blocks along `direction` (only the horizontal part of `direction` is used; `distance <= 0` does nothing; `lift` is the upward launch velocity in blocks per tick). The launch speed is solved from vanilla motion (`PullMath`, per tick of `LivingEntity.travel`: move, land at y = 0, gravity `vy = (vy - 0.08) x 0.98`, horizontal drag 0.91 in the air and 0.546 = 0.6 x 0.91 on the ground chosen by whether the body stood on the ground at the start of the tick, a component under 0.003 is zeroed) by bisection (`speedFor`, 60 steps, deterministic, no randomness). The speed cap is 8 blocks per tick, about 60 blocks of pull. The three pulling skills rest the victim 1.5 blocks in front of the caster; `PullLandingScenarioTest` asserts the resting band at 3 / 5 / 12 / 30 blocks.


**rev4.7 (2026-10-29, founder Q7 = A)**: `screen_flash` and `screen_veil` are presentation ops again (10.1b rows; strict decode: overlay enum, `#RRGGBB`, veil distance 0.3..1.0 and `max_screen_fraction` up to 0.18 refused instead of coerced). `screen_flash` is a point effect through `ISkillGameplayGateway.screenFlash`; `screen_veil` is instance-lived (`spawnScreenVeil` / `updateScreenVeil` per tick, `End` at `ticks`). Sources: v1 `MechanicYaml.kt:1065-1098,1131-1163`, `PaperPlayerFeedbackGateway.kt:53-71`, `ScreenVeilHandler`. Q7 is closed; `bastion-greatsword-s2` and `-ego` carry their v2 cues again.

**rev4.18 (2026-10-30, eye-ray chip)**: a shotgun block chip follows the live eye and look ray through the CEL function `aim_point(caster, N)` (`CelFunctions.kt:312`, live-world set `ExprClassifier.kt:32`); no new `break_area` shape (10.1c stays sphere / line / box). Shipped use: `content/skills/shotgun-blast/skill.yaml:50`. Sha 9afad8299, scenario `EyeRayChipScenarioTest`.

**rev4.19 (2026-10-30, reload and patchKind)**: every `patchKind` (`skin` / `balance` / `mechanic`) always applies; `allowGameplayPatches` is removed. A malformed patch fails loudly (`SkillReloadError.Rejected`) and the skill keeps its old version. Section 13; shas d24119229, d284e6b68; e2e `SkillEngineV3E2eTest#reloadAppliesSeparatePatchAndKeepsOldVersionOnBadPatch` and `#reloadAppliesBalancePatchOnNextCast`.

**rev4.20 (2026-10-30, dead v2 deleted)**: the v2 skill types, the `_skills-2` types, `SkillArchetype` and the old projectile line are deleted from the code. Shas 9b90f3dd0, 1f480512d, c005d49bc.

**rev4.21 (2026-10-30, tidy to the code, doc only)**: Status is Accepted (manager decision 2026-10-30). Q6 is closed as option A (the engine has no `damage_delay`). Rewritten to the code: the garbled rev4.5 / rev4.6 / rev4.7 text; section 6 (migration is done); 10.1 / 10.2 (nested machine is `start:` + `states:`, `parallel:` regions, `machine.tail` / `world`); section 12 (real test classes); section 13 (envelope has `fragments`, Patch example has `id` / `patchKind`, patchKind semantics, reload); Open decisions 1, 3, 4. No field is added or removed.

**rev4.22 (2026-10-31, sync to wp/se-integrate30 b5a81b4a1, doc only)**: the ADR had stopped at rev4.21 while the engine grew. Section 15 now records, with code paths: the `react` op (15.1); the `viewers` row filter, which shipped and was then REMOVED by the founder (15.2; the original "Rejected" note in Open decisions stands); dash clearance and `pass_through` (15.3); the weapon hold table, the `root` status and the ban on chance for hold statuses (15.4); the callee-only cast guard (15.5); the caster-eye clutter survival model (15.6); the founder answers of 2026-10-31 (billboard kept, sample-* deleted, patch = skin selection plus global balance overlay, hot reload as a baseline; 15.7). Q6 stays closed as option A (rev4.21). Nothing is added or removed in the schema by this entry.

**rev4.23 (2026-10-31, events_in part key, sync to main 243ff46d2, doc only)**: a part may declare `events_in: [name]`; each name is a caller event the part's riders end on, referenced as `until: "${name}"`. The call site binds every name with `events: {name: caller.event}`, and an unbound or unknown name is a load error. A part with `events_in` is called from an op only (a state-level `use:` is rejected) and does not emit `End(callId)`, so its riders live until the bound event. An op-level call with `every` + `until` expands against the caller's section horizon when the callee state has no `for`. A state-qualified bound frame (`frames: {weapon: /windup/rig}`) is spelled by its bare op id in `AnchorScope.host` (it used to resolve to a same-named caller op); a bound frame with a point loses the point, so bind the whole frame and name points inside the part. Moving rows into a part call changes their particle seeds (one instance-path segment more); the shared-parts migration accepted a one-time seed re-roll, proven by `tools/game-sim/scripts/oracle-normalized-diff.sh` (op labels and seeds stripped, diff empty). Cases: `PartEventsInScenarioTest`. ADR-0312 (particle frame basis) and ADR-0313 (summon / homing / path) extend this schema.

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
rejections: wrong rotation, late rotation, floating weapon, particles that are not the v1 particles. v2 then made it worse:
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
- **Rotation sign.** A rotation is `deg` about a named axis of its basis `(forward, left, up)`. Positive `deg` moves the basis axes
  cyclically **forward -> left -> up -> forward**: `+deg about up` carries `forward` toward `left`, `+deg about left` carries `up`
  toward `forward`, `+deg about forward` carries `left` toward `up`. Negative `deg` moves them the other way. This is the right-hand rule
  of JOML, Blender and glTF; clock-direction words are not used anywhere in this schema or its error messages
  because they need a viewpoint. Vanilla `yaw` is never written in a skill file (a display `yaw:` is a load error); the host converts
  (`yaw = -deg about up`).
- **Blade travel.** A swing is written by the direction the edge travels: name the axis, and the sign is the cyclic rule above
  (`spin axis: up, deg_per_tick: +12` = the tip travels forward -> left, a right-to-left horizontal slash for a right-handed caster).
- **Three worked examples** (a `spin` law each):

  | wanted | law | why |
  |---|---|---|
  | horizontal slash, tip sweeps from the caster's right across to the left (120 deg in 10 ticks) | `{law: spin, axis: up, deg_per_tick: 12, for: 10, start: [{axis: up, deg: -60}]}` | +deg about `up` carries forward -> left; start at -60 (tip right), end at +60 (tip left) |
  | overhead chop, tip from straight up down to straight forward (90 deg in 6 ticks) | `{law: spin, axis: left, deg_per_tick: 15, for: 6}` on a blade whose rest pose points `up` | +deg about `left` carries up -> forward |
  | drill thrust, blade rolls about its own length so its edge goes from left to up (one full turn in 8 ticks) | `{law: spin, axis: forward, deg_per_tick: 45, for: 8}` | +deg about `forward` carries left -> up |

- **Reverse check** (authors and reviewers; the skill workflow's reverse check): read the cyclic rule backwards. If the written sign moves
  the tip the wrong way, negate `deg`; never swap the axis.
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
  - {id: hand,   anchor: body_anim.hand_r, mode: follow}                               # a named point of the skill's own `body` clip; follows it every send
  - {id: muzzle, anchor: caster.eye, offset: {forward: 0.5}, mode: snapshot}   # copied once at activation, then world-fixed
  - {id: blade,  anchor: shears.blade_a, mode: follow}                                 # part anchor; axes long/edge/face from content/frames
  - {id: flash,  anchor: contact, turn: [{axis: up, deg: 90}], mode: follow}           # at a contact point; created when the event fires
```

| field | meaning |
|---|---|
| `anchor` | parent anchor: `world`, `caster` (feet, yaw-only basis), `caster.eye` (full look basis, no roll), `<body op id>.<point>` (a named point of the shared body skeleton, `points:` in `content/frames/body.yaml`, read through the `body` op's clip; rev4.8, rev4.9 G6), `<rig op id>.<point>` (a point of the rig's `part`, 10.5; rev4.8), `<item-frame>.<part>` (axes from `content/frames/<item>.yaml`), `contact` (builtin: the point where the cast's hit landed, a reserved name since rev4.9 F1; an op or anchor of that name is a load error, write `hit_pt`), `victim.<root/center/head>`, or any declared anchor. A gameplay frame is a valid parent (read-only). |
| `offset` | name-keyed map in the PARENT's axes. |
| `turn` | list of `{axis, deg}` relative to the parent basis, applied in list order. |
| `mode` | `follow` (default): origin and axes re-read from the parent at every send. `snapshot`: pose copied once at activation, parent becomes `world`. |
| `of`, `offset`, `sweep`, `to`, `samples` | every `anchor:` reference may be the map `{of: <anchor>, offset: {...}, sweep: <blocks>, to: <anchor>, samples: <n>}` instead of a bare id: `of` is the parent, `offset` is in its axes, `sweep` spaces one call per that many blocks of the anchor's travel since the last send (R-anchor; a particle or ribbon only), and `to` + `samples` (>= 2, a literal) makes a spatial segment from `of` to `to`: `samples` rows in the same tick (rev4.8; `particle` and `particle_def`, plain anchor names at both ends). Example: `anchor: {of: body, to: tip, samples: 8}`. |
| `caster.look` | feet origin, axes = yaw (left, forward) and PITCH (forward tilts, up follows). `caster` keeps the yaw-only basis; `caster.eye` is the eye origin. |
| `contact` frame | origin = `contact.point`; up = `contact.normal` (an entity hit: world up); forward = the caster's yaw forward made orthogonal to up. Created when the event fires. |
| `axes` | on a frame: `caster | victim | world`, which parent's axes the frame's offset map reads (R23). |
| `victim.*` | `victim.root` (feet), `victim.center` ((feet + eye height) / 2, read live each tick; `caster.center` and `target.center` likewise; 0.81 for a standing player), `victim.head`. **Q2 RESOLVED (founder 2026-10-29)**: no fixed `victim.chest`. |
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

**Held item and rig points (rev4.13).** A main-hand mesh is bound to the item the cast was started with: `CastRequest.heldItem` (the triggering `sourceItemId`, else the skill's own `item:` when it is a concrete id) is fixed at cast start and travels as `CastRef.heldItem`; the visual gateway draws `DisplayKind.AnchorHeldItem(itemId)` in `THIRDPERSON_RIGHTHAND`. It never reads the live main hand, so an emptied or swapped hand cannot abort or change the cast. Because the mesh must be known at load, `display item: main_hand` needs a concrete skill `item:` (not `any`): otherwise it is a `DecodeAbort` (`display item main_hand needs a concrete skill item`). The points of a held rig (`rig.tip`, `rig.root`, `rig.grip`, ...) are in the rig frame at `PLAYER_SCALE` = 0.9375 (the scale of the mesh and of the skin rig); a rig with `of:` is only a carrier and its points are not scaled.

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
  entries (`count: [2, 3, 5]` with `times: 3`); a list of the wrong length is a load error (`SkillDecodeError.Invalid`, `IndexedListLength`). Entries may be numbers, `= expr` strings, `[x, y, z]` triples, or
  (rev4.8) plain strings such as `block: [gray_concrete, iron_block, black_concrete]` (quoted into a CEL string literal and folded per copy). `motion` (and other fields that decode their own lists) is excluded from R28.
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
written in the field table. A value that differed in v1 is written EXPLICITLY in the skill, never supplied by the engine.

| field | choice | why |
|---|---|---|
| `fade.exponent` / ribbon `fade` (v1 `fade_power`) | identity default **1.0** (linear); a v1 look that used 1.5 writes `exponent: 1.5` on that element (v1 defaulted to 1.5, v2 silently changed it to 1.0, unapproved) | the schema default is the neutral value, and the v1 look is visible in the content instead of in the engine |
| `count`, `offset`, `extra` (particle) | identity 1, [0,0,0], 0 | vanilla overload arguments, written down |
| `scale`, `size` growth, `volume`, `pitch` | identity 1 (volume/pitch: vanilla sound defaults) | |
| `billboard`, `context`, `view_range`, `pitch` | vanilla `fixed`, `fixed` (decision), 1.0, 0 | |
| `ribbon.style` (`soft | crisp | both`), one of `ribbon.width_frac` (with `root`) / `ribbon.width` (without `root`), `projectile.block.mode` | **required** (v2 hid `soft`) | no neutral value exists |
| display `line_width`, `text_opacity` (kind text) | **listed defaults** 200, 255 | the vanilla text_display values (line width 200, opacity 255 = opaque); not identities, so the schema prints them as `x-listed-default` |
| sound `category` | **listed default** `master` | vanilla `SoundCategory.MASTER` |
| `frame.mode` | **listed default** `follow` (the other value is `snapshot`) | the engine value; follow re-reads the parent at every send |
| `align.axis`, `stretch.axis`, `align.up` | **listed defaults** `forward`, `forward`, `world.up` | the engine values: the object's forward points at the target, up is kept toward the world up |
| `orbit.face` | **listed default** `keep` | the engine value: the object does not turn with the orbit |
| reactive law `step` | **listed default** 2 (ticks between re-sends, minimum 1) | the engine value; a live law is re-sent every 2 ticks |
| ribbon `step_deg` | **listed default** 30 (degrees between samples, 5..90) | the engine value |
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

**Line ribbon volume (rev4.15).** A `ribbon` with `from` / `to` (look `shape: line`) is a thread of `thickness` blocks drawn, per segment, as ONE `item_display` of the pack model `astera:skill_line`: two zero-thickness planes crossed at 90 degrees, each with a front and a back face, so the thread has thickness on both perpendicular axes, is visible from every side and never vanishes edge-on or is culled. The display matrix is `[dir*length | u*thickness | w*thickness]` (`LineGeometry.frame`, one function for the game-sim `GatewayRecorder` and `PaperSkillVisualGateway.spawnLine` / `updateLine`; `u`, `w` from `LineBasis`, camera independent), `itemDisplayTransform: NONE`, billboard FIXED. This replaces the two TextDisplay background quads of rev4.13 (a text background culls its back face: each quad was invisible from one side); the lifetime and the per-tick endpoint updates are unchanged.

**Line length and polyline (rev4.14).** Length: a segment's model spans one block per scale unit, so the matrix column `along` is the segment vector itself and `across` / `normal` are `u * thickness`, `w * thickness`; no per-block constant (`SOLID_SCALE_X`/`SOLID_SCALE_Y` serve the trail only). Polyline: `segments: N` (a literal whole number 1..24, default 1) splits the thread into N pieces, `v_0 = from` and `v_N = to` exactly; every inner vertex is `from + (to - from) * (k/N + a_k) + u * s_k + w * t_k` where `u`, `w` are the two unit vectors perpendicular to the from-to direction (built from the direction alone, never from the eye, so the bolt does not swim when the camera moves), `s_k`, `t_k` lie in `[-spread, spread]` and `a_k` in `[-along, along] / N` (`LinePath.vertices`, one function for the sim and Paper). `jitter` keys: `spread` (blocks, literal > 0, required), `along` (fraction of one segment, literal in `[0, 0.5)`, default 0), `every` (re-jag period in ticks, literal whole number >= 1; absent = rolled once at the spawn tick), `follow` (the id of another line ribbon of the same handler / body; this line reuses that line's vertex stream, so a glow hugs its core). The offsets are visual randomness: a pure function of (row seed, epoch = `(tick - spawn) / every`, vertex, axis), the same on every replay; the segment count, the epochs and the end are table facts (all jitter values are literals, no CEL, no timing from `rand`). A re-jag is a cut, not a slide: a polyline's item displays have `teleportDuration` / `interpolationDuration` 0, a straight thread keeps its one-tick glide. Load errors (`RibbonOpSpec`, `PolylineFollow`): `segments` / `jitter` on a trail; `segments` not a literal whole number in 1..24; `segments` >= 2 without `jitter` (it draws the straight thread of 1); `jitter` with `segments` < 2; an unknown `jitter` key; `spread` missing or <= 0; `along` outside `[0, 0.5)`; `every` not a whole number >= 1; `follow` blank or naming the ribbon itself; `follow` naming something that is not another line ribbon of the same list of ops (same enclosing path, same copy index or unindexed); `follow` onto a line with a different `segments`. Display cost: a line weighs `segments` displays in check 10 (11.4).

### 3. Motion as rates and laws

Motion is a list on the object (`motion:`) or on an anchor (`motion:` in `anchors`, presentation anchors only). Laws are composable.

Every law has its own window: `at` (ticks since the instance started, identity default 0; the spelling `from` is a load error, rev4: `from` is
reserved for RANGES such as `speed: {from, to}` and for the projectile origin), `for` (ticks; or `until: <event>`; default to the end of the
instance), `every` + `times` (a law that restarts: `spin ... every: 10, times: 3` is three windows), and `ease` (`linear` default, names and total-vs-rate semantics in 3.7). A law is an ordinary part of the timing grammar (2.0): same words, same meaning, relative to the instance. Axes are named (3.1) and RESOLVED ONCE at law start, then frozen in the parent basis (a spinning object does not wobble).
Closed-form: all laws are evaluated as functions of `tau = ticks since law start`, never integrated tick by tick.

| law | fields | meaning |
|---|---|---|
| `spin` | `axis`, `deg_per_tick`, `accel?` (deg/tick^2) or `drag?` (0 < f < 1, rev4.10) | `angle(tau) = rate*tau + accel*tau^2/2` about `axis`; with `drag`: `rate * (1 - (1 - f)^tau) / f` |
| `move` | `dir` or `velocity` (name-keyed or CEL), `speed`, `accel?` (blocks/tick^2 along the velocity), `gravity?` (blocks/tick^2 down), `drag?` (0 < f < 1, rev4.10) | `v' = v * (1 - drag)` per tick; closed form `velocity * (1 - (1 - f)^tau) / f` per segment (excludes `ease`) |
| `orbit` | `center` (anchor), `axis`, `deg_per_tick`, `phase_deg?`, `face?: keep/tangent/center` | position rotates about `center` along `axis`; radius = distance at law start |
| `grow` | `prop?`, `per_tick` (vector or number) + `accel?`, or `to` + `for` + `ease`, or `by` (rev4.8: factor on the held scale, ends at `start * by`; exactly one of `per_tick` / `to` / `by`; scale/size only, no `accel`) | change of a property: display `scale`/`size`, particle `count`/`offset`/`extra`, sound `volume`/`pitch` |
| `fade` | `exponent?` (1.0 = linear, see 2.0b), `for` | the intensity property goes to 0: display `text_opacity` (text/sprite; item/block displays have no alpha, use `grow` to zero), particle `count`, sound `volume`. `a = (1 - tau/for) ^ exponent` |
| `align` | `axis: <named>`, `to: velocity \| toward(<anchor>) \| <named axis/CEL vector>`, `up?`, `for` + `ease?` (rev4.10: blend from the held rotation, one live write per tick of the window) | orients the object axis; `to: velocity` of an attached `move` is constant over its segment, so it is ONE write |
| `follow` | `anchor`, `offset?`, `lag?`, `step?` | position tracks another anchor; per-step (live) law |
| `stretch` | `axis`, `from`, `to` (anchors) | length of the object along `axis` equals the distance between two anchors; this is what `line` expands to |
| `keys` | `[{t, pose?, offset?, size/scale?, ease?}]` | irregular choreography only (3.3) |

#### 3.1 Named axes

Resolution order: the object's own axes (`long`, `edge`, `face` for item parts; `face` = display normal for sprites), then its anchor's
(`forward`, `left`, `up`), then `world.up` / `world.forward`. An axis may also be `{in: <anchor>, vec: {forward: 0.2, up: 0.9}}` for a non-named direction.

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
function (`pos vel speed axis forward up left right dist dist_xz in_cone behind`) on `caster` / `target` / `victim` / `enemy` or an anchor of
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
| `radius`, `hit` | the hit capsule and victim rule. `hit.targets`: `enemies` / `allies` / `all` / `blocks_only`; `hit.once`; `hit.times` (pierce count, required when `once: false`); `hit.attach` (required, no default; rev4.9 G2): `true` sticks the body to the first victim, riding the anchor named by `hit.attach_to` (`victim` or `victim.<point>`, required with `attach: true`, refused otherwise; needs `once: true` and `block.mode: stick`), then `stick` fires and `unstick`+`end` follow after `block.for` ticks. Collision is **swept**: each tick the engine tests the capsule swept from the previous body position to the current one, so a fast body never tunnels and there is no substep count field (RTM's `repeat`/`maxRepeat` are not imported). The contact point is the first point of the sweep that hits. |
| `motion` | laws on the body frame (3). `spin` here is a frozen-axis rotation of the body basis; displays `anchor: ball` ride it (one segment write for a constant spin). `align: {axis: forward, to: velocity}` keeps the nose on the path through bounces (one write per bounce). |
| `block` | `mode` required: `despawn` (events `end`), `stick` (`for` ticks stuck, or `fused: true` = stuck until the flight end, rev4.12; which extends the flight end window to `[1, for + block.for]`; events `stick`, `unstick`, `end`), `bounce` (`restitution`, `friction` and `times` required = max bounces; events `bounce` with `event.i`, rolling after the last; ends only at `range`/`for`; rev4.12), `pass` (no block events; `range`/`for` end it). |
| `on` | handlers keyed by event: `hit`, `bounce`, `stick`, `unstick`, `block` (any block contact, before the mode's own event), `expire`, `end`. `expire` fires just before `end`, ONLY when the body ends naturally by `range` or `for` (not on a hit, a block mode end or cancel). Each value is a `do` list; `at: 0` = the event tick; `contact`, `target` / `event.victim`, `event.i` are bound inside. `end` fires exactly once per body, after every other event, also on cancel. On one tick the order is fixed: body motion, swept collision, then the events in the order `hit` (per victim, declaration order), `block`, the mode's event (`bounce` / `stick` / `end`), and their `@0` handler rows in that same order (10.2a). **Handler sub-tables are owned by the occurrence (the cast), not by the body**: an `on: hit` explosion keeps running after the body despawns; `contact` and the body's last pose are **snapshots** taken at the event tick (example j: `frames: {origin: ball}` in `on: end` is the last pose). A handler list may hold groups, displays, `use:` parts, `use: skill:` calls and gameplay ops; it is compiled to a sub-table (C3). In `on: expire`, `contact` is the body's position at the end of its flight (where `range` / `for` ran out, normal = up), rev4.13; a gun draws its miss tracer from it. |
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
| `segments`, `jitter` | line only (rev4.14): a polyline of `segments` pieces (1..24, literal) whose inner vertices are displaced by `jitter: {spread, along?, every?, follow?}`; see section 2 "Line length and polyline" |
| `of`, `root` | the traced anchor; `root` (optional) is the anchor the trail width is measured from |
| `history` | ticks of tip history drawn; required on a trail, forbidden on a line (this is not `lasts`, which stays the occurrence life) |
| `style` | `soft`, `crisp` or `both`; required |
| `width_frac` | width as a fraction of root-to-tip; requires `root` |
| `width` | width in blocks; forbids `root` (a projectile has no root). Exactly one of `width_frac` / `width` is required |

- Owner is the cast. The runtime registry is keyed by cast id; cast end/cancel/death/disconnect/unload removes everything owned. A cap
  (`max_life_ticks`, server config, default 1200) applies even to `for: cast`.
- Every spawned entity carries the PDC tag `astera:cast=<uuid>`; at boot and on chunk load a sweeper removes tagged entities with no live owner.
- `remove: [ids]` stays for early ends; it is never the only end.
- Content known or suspected to rely on a missing end, to be confirmed by the load-time check on first run of this schema (not yet audited by
  capture): `couture-rapier-s2` coin 1 streaks (confirmed by founder); `bastion-greatsword-def` `bastion-rim`/`crest` (5 plates, one remove at
  tick 162 while the v1 elements live 71-99 ticks); `orrery-s2` orb/impact/glint (plates `impact_violet_flash`, `impact_long_violet_flash`, annulus
  removed only by event handlers that fire on hit; staff trails, emitter light removed at fixed ticks).

### 6. Migration (no converter)

Founder 2026-10-29 (memory `project_skill_engine_v3_decisions`): no converter. The kept Limbus skills (`bastion-greatsword-*` x4,
`couture-rapier-*` x5) and every basic skill are **hand-written in v3** from their beat sheet. This is done: every file under `content/skills/` is a
v3 document and `ShippedV3ContentScenarioTest` (A9) loads, expands and replays all of them. A v1 or v2 file is not an input: it is re-authored, and a
file on another `apiVersion` fails to load. The v2 skill types, the `_skills-2` types, `SkillArchetype` and the old projectile line no longer exist (rev4.20).

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

| old (0301 / v1) | new | vanilla argument |
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
| `emit ... spacing: s` | `particle anchor: {of, sweep: s}` (temporal) or `anchor: {of, to, samples: n}` (spatial, rev4.8) | one call per sample |
| custom `emit particle: <def>` | `op: particle_def, def: <def>` | n/a (engine particle) |
| `updatePose`/`updateScale` (2-tick scale, 1-tick pose) | one segment write | `interpolation_duration` |
| `HeldItem` mesh (cloned stack, THIRDPERSON_RIGHTHAND) | `item: main_hand` resolved to model id, `context: fixed` (open decision 1) | `item_display` context |
| world-teleported "attached" mesh | `mount: caster` | passenger |
| `size_frac` (x parent frame distance), glyph x5 | `size` in blocks | `scale` |
| timeline group `every` loops, separate `sound every`, `emit spacing` loops, `at` + `until` scattered | the one timing block `at / for / every / times / until / lasts` on every op | n/a |
| `fade` default 1.0 (v1 `fade_power` 1.5) | `fade.exponent` identity 1.0, a 1.5 is written explicitly | n/a |
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

**Callee frame names (rev4.13).** A `frames_in` name of a called skill, part or fragment is spelled by the caller's frame wherever the table names a point: one resolver, `AnchorNames.host(scope, name)`, turns `spot` (bound as `frames: {spot: contact}`) into `contact` (keeping a `.point` suffix) in every cell the player reads: particle / sound `anchor`, `to`, `shift`, area `around`, display / frame `anchor`, ribbon `from` / `to` / `of` / `root`, projectile `origin` and hit `anchor`. A callee compiled on its own (content-lint) sees its own names; a `frames_in` the call does not bind is still a load error naming it. This is what lets one callee (`content/skills/gun-tracer`, an instant line from `from` to `to`) serve six guns: each caller binds its muzzle and its hit / expire `contact`. Muzzle position (rev4.14): a gun's `muzzle` / `flash` anchor is the hand, `caster.eye` + `{forward 0.45-0.75, left -0.35, up -0.65}` (per gun), while the projectile `origin` stays on the look axis, so the line starts at the hand and ends at the contact the crosshair decided.

#### 9.3 Derived documents (`extends:` + `with:`, rev4.12)

A tier of a weapon or tool (`pistol-shot-t2`, `axe-fell-iron`) differs from its base in a few numbers, a different item and its own meta. It is written as a derived document instead of a full copy:

```yaml
apiVersion: astera/v3
kind: Skill
id: pistol-shot-t2
extends: pistol-shot
item: pistol-t2
meta: {name: {...}, description: {...}, cooldown_ticks: 24, resource_cost: 0, tags: [...]}   # written in full, never inherited
with: {dmg: 32.0}
```

Rules: a tier document has exactly `apiVersion, kind, id, extends, item, meta, with` (any other key is a load error naming the key and the base). The base is a composed skill id (a document of `content/skills`, never a Part, a Patch variant or a tier); one level only. `meta` is required and is the tier's whole meta (a missing `meta`, or `item` when the base has one, is a load error: nothing is inherited, founder 10/29 "全部書かせる"). `with:` maps base params to literals (no `= `, no `${}`); each name must be a param of the base, its type must match (`number`/`ticks`/`blocks`: number, `bool`: bool, `string`: string), and every `required` base param must be given. The derived tree is a copy of the base's same-id merged tree (the base's Patches are not applied to it) with the tier's `id`, `item`, `meta`, and `params.<p>.default` replaced by the `with` value; from there it composes, decodes, compiles and replays like any skill, so its oracle golden is `skill-oracle/<tier-id>/skill.txt` and equals the base with the values substituted. Patches may target the tier id (`target: pistol-shot-t2`, variant `pistol-shot-t2@x`): they apply to the derived tree after derivation. Editing the base file recomposes its tiers on incremental reload. Load errors surface as `ComposeError.Extends(file, path, reason)`; the texts are fixed in the design (`extends 'X': no skill document 'X' in content/skills (...)`, `... a base must not itself extend (...)`, `with 'p': base 'B' declares no param 'p' (params: ...)`, `with 'p': expected number, got string "32"`, `extends 'B': 'meta' is required in a tier (it is not inherited)`). Cases: `ExtendsTierScenarioTest`.

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
| state | named; has `enter`, `do`, `exit`, `on`, `for`, `then`, optional `final: true`, optional nested sub-automaton spelled `start:` + `states:` inside the state (no `machine:` key; `start` absent = `NestedStartMissing`, `SkillDocumentDecoder.kt:182-185`), optional `parallel: [ {start, states}, ... ]` regions (`PartParser.kt:188-215`) |
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
| `status` | `target`, `status`, `stacks`, `for`. A victim that damage of THIS cast already killed gets no status: `damage` returns whether it killed, the player keeps the set per cast and skips later `status` rows on those victims, recorded as `status_skip <victim> dead_victim` in the trace (rev4.13; the skip is never silent). |
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
| `break_area` | `shape` one of `sphere: {radius}` (centred on the anchor; an offset is written on the anchor), `line: {length, width}`, `box: {width, height, length}`; `tool` (`axe` \| `pickaxe` \| `shovel` \| `none`: which blocks break and which drops apply); `block_damage` (0..2000); `ripple` (ticks to spread the cells over); `revert` (1..6000 ticks until the blocks are restored); `outside_arena` (true allows breaking beyond the arena bound) |
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

- A state may contain a nested machine, spelled `start:` + `states:` inside the state (or a `use:` call, which is a nested machine). Entering the state enters the child's `start`. A state with `parallel: [ {start, states}, ... ]` runs every region and ends when all of them are final (`PartParser.kt:188-215`, `PartModel.kt:52`, `PartMachineWiredScenarioTest#parallelRegionsEndWhenAllFinal`). The top-level `machine:` keys are `start, states, world, tail` (`SkillDocumentDecoder.kt:163`).
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

1. state rows at this tick, table order: `enter`/`exit` bookkeeping, `spawn` (frames and projectile bodies first, so a rider listed before its body still finds it), `key`/`teleport`, `particle`/`sound`, gameplay (`hit`, `damage`, ...), `lock`, `call`, `emit`, `end`;
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
8b. **Handler certain exit (rev4.14, `HandlerCertainExit`)**: a non-detached presentation row of a body's handler (`hit`, `block`, `expire`) that plays after the handler's firing tick, while the same body makes the owner's exit certain on that tick (`hit` with `once: true` and neither `reflect` nor `attach_to`; `block` in `despawn`, `stick` or `bounce` mode; `expire`) and the owner (or an ancestor) has a branch on that exit event, is a load error: it would be cut every time. Mark it `detach: true` or move the transition. Gameplay rows pass (rule D drains them; check 8 does not bound a drained handler gameplay row by its state's length).

#### 10.5 Presentation stays mathematical

States do not change the visual grammar: displays use frames + laws + CEL (sections 1-3, 3.5). A state's visuals are written against bound frames
(`weapon.tip`, `body`), with `t` / `lt` as the clock; the automaton only decides WHEN a display lives and WHICH set is active.

Rig additions (rev4.3): a rig takes `of: <frame id>` (the frame the rig drives; identity: the held item, and block displays follow it) and a `reset: true`
step, a duration-0 write of the rest pose (R30; `MAX_STEP_DEG` is unchanged). `part` and `edge` are rig-step FIELDS, not events.

**The rig is an anchor instance (rev4.8).** The rig fields are `part, mount, anim, rest, tilt, freezes, respect_hit_stop, body_respect_hit_stop, swing, for`
(plus `of`). `swing` is the rig's only law: the engine lowers its few keys (`{at, for, from, via, to, edge, roll, ease, reset, turns}`) to one pose per tick, always,
so there is no sampling field and no baked blade/tip/root/edge/hand field. `part` names a part of `content/frames/<item>.yaml` for the driven frame: the part's
model points (`tip`, `root`, `grip`, ...) and its `long` / `edge` axes become the rig instance's anchor points, read per tick as `<rig id>.<point>` in
anchors (`anchor: {of: rig.root, to: rig.tip, samples: 8}`) and in CEL. `mount: caster.hand_r` (the only value) rides the driven frame on the caster's arm
(origin = grip, rotation = arm * tilt * pose * rest); an absent `mount` leaves the frame at the parent's origin (the old implicit hand is gone). The rig
declares its instance with `AnchorRegistry.declare` and is registered in the run's `recs` like any other instance, so there is no separate rig table.

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

**Recommendation for content: the declarative form by default.** Reasons: (1) cadence and extent are readable in one place ("every 4 ticks for 24"), where the imperative form spreads them across `wait`s; (2) edits are local (change `every: 4` to `3`), where an imperative edit changes every later tick; (3) it expands by arithmetic, with no order dependence, so the lint can compare and diff tables. Use `seq:` only for a short one-off sequence of distinct beats where each step differs (for example hit-stop, then flash, then sound) and the count is 2-4.

#### 11.2 Static check 8 and what the table also gives

8. **Bounded timing**: every `at`, `for`, `every`, `times`, `lasts` resolves to an integer at expand time (S/T evaluated), every `until` to a finite horizon (2.0). Unbounded timing is a load error.

The same table also feeds: the writes lint of 3.5, the min/max path lengths used for cooldown and animation-lock sanity checks (a skill whose
worst-case path exceeds `max_cast_ticks` fails), and the Board's per-item "durations and hits" evidence.

#### 11.3 The dump: `astera skill expand <id>` (C1, C2)

The dump is the serialised `ExpansionTable` (skeleton `design/skeleton.md`), one text block per machine level, deterministic ordering (state in
declaration order, tick ascending, op in declaration order, occurrence ascending). Example j's comments ARE this format. A row lowered from an `anchor: {of, to, samples}` segment carries ` sample k/n` (k from 0) after its payload; the token is printed only on those rows (rev4.8). Grammar:

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
(offset), `align:<axis>><anchor>^<up>` (rotation), `stretch:<base>><tip>^<ax>,<ay>,<az>` (offset = the base point, rotation = the object axis points base to tip, scale = the anchor distance along the axis; resolved by the shared TablePlayer, rev4.10), `cel:<source>` (class L); `targets:` never (structured).
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
    must not exceed `max_live_displays` (server config, default 64); the table prints the peak and the tick. The peak is counted over `[spawn, end)` (rev4.4, `e337594b2`): the player queues an `End` and removes the instance in the same tick's cleanup, so a display that ends on tick T never coexists with one spawned on tick T. Rev4.14 (`483053cd5`, `82cc67df1`): a `shape: line` ribbon weighed `2 * segments` displays; since rev4.15 it weighs `segments` (one item display per segment; a trail and every other display weigh 1). The peak is the max over ticks of the weights alive at that tick, computed from load-fixed intervals: the rows of one handler are placed relative to one firing, so those that end inside the handler count as ONE relative peak spread over the event window (first spawn at `window.min` to the last `End` at `window.max`); a row without an `End` in its handler is alive to the state's exit; a detached display is closed only by a detached `End` (its own `for:`), and a detached display with no such `End` counts to cast end + tail; the detached peaks of different units are still added (two units are assumed to peak together).
11. **Bounded instances**: every op's instance bound (product of enclosing group bounds, `times`, part call count, bounce `times`) is finite and at
    most `max_instances_per_op` (default 256); an `every` carrying both `for` and `times` is a load error (2.0); a group deeper than `max_group_depth` is an error.
12. **Table closure**: every event named in `until:`, `at: <event>`, `on:` or a transition has a producer in scope (built-in for the kind, a
    projectile of this cast, a part's `emits`, an `emit:`), and every sub-table is reachable from some event producer; an unreachable sub-table or
    an unproduced event is an error (sharpens 10.4 check 5 for event-relative sub-tables). Also: the longest same-tick chain of `@0` rows that
    produce events (10.2a) is at most `max_same_tick_depth`.

### 12. Acceptance suite A1-A9 (the definition of "the engine implements this ADR")

All tests are game-sim scenarios (`tools/game-sim`, real loaders, real `SkillComposer`, in-memory gateways from `plugin:test-fixtures`) or real-content
loaders; no unit tests (coding posture). The fixtures are the ADR examples themselves, so the ADR cannot drift from the engine. The suite is in place and
is the standing regression gate (rev4.21: the table below lists the classes and cases that exist, not the planned names of rev1-4).

| id | class (`tools/game-sim/src/test/kotlin/.../skillengine/`) | asserts | fixture |
|---|---|---|---|
| A1 | `AdrExamplesLoadScenarioTest#everyExampleLoadsAndExpands` | each `docs/adr/0303-examples/*.yaml` decodes with zero errors, lowers to an `ExpansionTable`, and the dump equals the `# expand:` block in the file's comments where that block is in the dump grammar | examples a-j |
| A2 | `AdrExampleBehaviourScenarioTest#<letter>...PlaysItsTable` (one case per example a-j) and `#displayYawIsAnUnknownKeyNamingRotate` | playing the cast in the sim produces the gateway call sequence the table lists, tick for tick; scripted events paste the event sub-tables at those ticks only | examples a-j |
| A3 | `TimesAtScenarioTest` (`listAtGivesEachCopyItsOwnTick`, `celAtReadsTheCopyIndexAndForCanEndAllCopiesTogether`, `badPerCopyAtIsANamedLoadError`), `TimerParamScenarioTest`, `StaticChecksScenarioTest` | timing blocks (`at`/`every`/`times`/`until`/`lasts`) fire at the printed ticks; bad timing is a named load error. There is no `TimingBlockScenarioTest`; timing is asserted inside the per-op and example scenarios | inline YAML, examples d, j |
| A4 | `CompositionScenarioTest` (`withArgsAreTypedAndRequired`, `recursionIsLoadError`, `skillCallUngatedByDefault`, `skillCallGateCooldownAndCost`), `ComposeRulesScenarioTest`, `CalleeFramesInScenarioTest`, `PartFramesOutScenarioTest` | part rows are inlined under `<call id>/`; typed `with:` args; recursion is a load error; cost/cooldown gateway calls appear only with `gate:` | examples e, f, j |
| A5 | `MotionLawsWiredScenarioTest` (`orbit`, `align`, `follow`, `stretch`, `twoWritesOnOneTickIsMachineCheck12`, `keysTurningOver170DegreesIsALoadError`, `moveHasNoAccel`, `moveWithDragIsGeometric`, `spinWithDragIsGeometric`, `dragWithEaseOrAccelOrOutOfRangeIsLoadError`, `alignBlendsOverWindow`, `alignBlendNeedsForAndRefusesStep`, `stretchReplaysBetweenBuiltinAnchors`, `stretchReplaysBetweenSkillDeclaredAnchors`, `stretchRollFollowsTheParentFrame`, `alignToWorldUpRollsTowardTheParentForward`, `followReplaysOnASkillDeclaredAnchor`), `MotionLawGrammarScenarioTest`, `PointLawsScenarioTest` | per segment write: translation / rotation / scale equal the closed form at the segment end | examples a, d, i; inline YAML |
| A6 | `ProjectileScenarioTest` (`stickUnstickEvents`, `hitPierceTimes`, `blockModeFieldsRequired`, `unboundedLifeIsALoadError`, `untilMaskNeverWidensTheEndWindow`, `bodyCapsuleHitsAlongOwnAxis`, `partNeedsItsPointAndAShape`), `ProjectileDisplayScenarioTest` | body position against the integrated speed ramp; the `on:` sub-tables pasted at the event tick; missing required block-mode fields are load errors | examples b, f, j |
| A7 | `PartMachineWiredScenarioTest` (`parallelRegionsEndWhenAllFinal`, `fragmentFromHitAndMissHandlers`, `fragmentRecursionIsLoadError`, `undeclaredFragmentIsLoadError`), `MachineTablesScenarioTest`, `LockCancelScenarioTest`, `TablePlayerRulesScenarioTest`, `StaticChecksScenarioTest`, `GuardCompileScenarioTest` | the active state per tick; exit/transition/enter order; the lock window; cancel order; each 10.4 check has a rejected inline YAML | examples e, g; inline YAML |
| A8 | `StrictDecodeScenarioTest` (`r1`..`r14`, `rejectedExamplesFailWithNamedError`, `noHiddenDefaultsInSchema`, `documentAnchorMountIsUnknownKey`, `documentAnchorModeRequired`, `documentAnchorOffsetAcceptsCel`) | every `0303-examples/rejected/*.yaml` fails with the `SkillDecodeError` subtype named in its first comment line; the generated JSON schema has no `default` other than identity values (2.0b); unknown keys at any depth are errors | `rejected/r1..r14` |
| A9 | `ShippedV3ContentScenarioTest#everyShippedSkillLoadsExpandsAndReplays` and `#everyComposedIdHasAGolden` | every shipped skill and part under `content/` loads, passes checks 1-12, expands and replays in the sim | real content |

Each engine change adds its cases to the matching class (no work-breakdown packages any more). A9 is the gate for shipped content: a skill is "done" when it
is in A9 and the founder has judged it on skill-compare.

### 13. Document envelope and Patch documents (rev4.3, R35, R38)

**Envelope keys (R38).** A skill document's top-level keys are exactly `apiVersion, kind, id, item, importance, meta, params, frames_in, emits, anchors, conditions, fragments, machine` (`SkillDocumentDecoder.kt:314-316`);
any other key is `SkillDecodeError.UnknownKey`. `frames_out` is a Part-document key only. There is no `presentation` key (presentation is ordinary ops in the machine, 10.5). A tier document (`extends`) has exactly the keys `id, extends, item, meta, with` (`Extends.kt:14`).

**Patch documents (R35).** A variant of a skill is a document of `kind: Patch`, applied BEFORE compile to the target's decoded document:

```yaml
apiVersion: astera/v3
kind: Patch
id: couture-rapier-s2-hard         # the patch document's own id
target: couture-rapier-s2          # the skill id being patched (a part is `part:<id>`, overlay patches only, no variant)
variant: s2-hard                   # id of the resulting variant; non-blank, no '@'
patchKind: balance                 # required: skin | balance | mechanic
ops:
  - {path: "machine.states.coin1.do[hit1]", $patch: merge, value: {damage: {physical: 40}}}
  - {path: "machine.states.coin1.do[snd]",  $patch: delete}
  - {path: "machine.states.raise.do[glow]", $patch: replace, value: {id: glow, op: display, ...}}
```

`$patch` is `delete`, `replace` or `merge`. A path addresses rows by id (`do[<id>]`), not by list position, so inserting a row elsewhere does not break the patch.
A `path` that does not resolve to exactly one node is a load error (`PatchPathUnresolved`, 10.4 check 8). The patched document is then compiled like any other,
so every check of 10.4 and 11.4 applies to the variant. The document keys are `apiVersion, kind, id, target, variant, patchKind, ops` (`PatchApplier.kt:42`); `ops` is non-empty, each op has `path`, `$patch` and `value`, and the addressable roots are `params, meta, anchors, conditions, machine`.

**`patchKind` (rev4.19).** `patchKind` is required and is one of `skin`, `balance`, `mechanic` (`PatchApplier.kt:59-60`). It limits what the patch may touch, checked by `PatchKindCheck` (check 13, `PatchKindCheck.kt:28,34`): `skin` edits presentation rows and the string params only presentation rows read; `balance` replaces a non-string `params.<p>.default` or a numeric field of a gameplay-op row with a number; `mechanic` is unrestricted within the roots. A breach is `ComposeError.PatchError.PatchKindViolation`; an unresolved path is `UnresolvedPath`. Every `kind: Patch` document is applied whatever its `patchKind` (`SkillComposer.kt:12`); there is no `allowGameplayPatches` switch any more.

**Reload.** `SkillComposer.recompose` is atomic: on any failure nothing changes (`SkillComposer.kt:75-84`). `reloadSkill` (`SkillEngineContents.kt:129`) turns a compose failure into `SkillReloadError.Rejected` (`:144`, `:164-167`) and keeps the old compiled definition of that id (a decode or compile failure is retried at the next reload, `:125-127`); a cast in progress finishes on its old definition and the new version affects the next cast. Proven by e2e `SkillEngineV3E2eTest#reloadAppliesSeparatePatchAndKeepsOldVersionOnBadPatch` and `#reloadAppliesBalancePatchOnNextCast`. **Q3 RESOLVED A (founder 2026-10-29)**: there is no `gameplay.expose`; a Patch document is the only way to vary a skill.

### 14. Input events and ports (rev4.3, R39, R40)

#### 14.1 Input events

`input.use | attack | sneak | swap | release`. `release` is the use key let go. The Paper mapping: `SkillChargeStarted -> input.use`, `SkillReleased -> input.release`.
Bare `charge` and `release` are NOT events (a load error, 10.4 check 5). `cancel` and `hurt` are the other caster-side events (10.1).

#### 14.2 Ports

Every port call takes `cast: CastRef(castId, caster, world)` as its first parameter, so a gateway never needs ambient state. Ports and rev4.3 additions:
`ISkillMovementGateway` (dash, impulse, lock, freeze), `ISkillItemGateway` (`consume_item`), `ISkillWorldGateway` (the 10.1c ops, `revertAll(cast)`),
`ISkillGameplayGateway` (adds `consumeStatus`, `cameraShake`, `stopSound`, `playBodyClip` / `stopBodyClip`). `currentWorld` is deleted (the world is `cast.world`).

### 15. Reactions, audiences, dash clearance, hold table, callee guard, clutter model (rev4.22)

Everything in this section is shipped on `wp/se-integrate30` (b5a81b4a1) unless a line says "in progress". Decisions are quoted with their date; a
decision without a founder quote is written "manager decision" and is open to the founder's veto at the next founder-check.

**15.1 `react` (victim body reaction).** Spec: `kernel/runtime/src/main/kotlin/com/astarworks/astera/core/application/skillengine/load/ops/gameplay/ReactOpSpec.kt`
(`ReactOp`, gameplay section, extended timing). Fields: `target` (required; only `event.victim`, so it lives in an `on.hit` handler), `flinch: true`
(the vanilla hurt animation tilted from the caster), `push: {back, up}` (blocks per tick away from the caster / upwards; `forward` is refused, write
`back: -x`), `hold: N` (ticks the victim's movement is held, a whole number >= 1 that folds at load), `player: {anim, speed}` (a body clip layer played
only when the victim is a player; a mob skips it by name, `react_skip`), and `for` (required, the window in ticks, >= hold; `every` / `times` / `until`
are refused). Scenario: `tools/game-sim/src/test/kotlin/com/astarworks/astera/gamesim/skillengine/ReactScenarioTest.kt`. Shipped use:
`content/skills/couture-rapier-s2/skill.yaml:63,131` (`flinch: true, for: 6`), `bastion-greatsword-s1`, and about 18 rows in total. Founder 10/30:
mob reaction C (skin animation for players only; mob puppets later) and hit-stun C (heavy skills only); "strength" numbers were left to the manager
(manager decision 2026-10-30, hold numbers superseded by 15.4). A mob victim gets flinch / push / hold only; the body clip skip is a decided
exception to "no silent drops" and is logged (`PaperSkillGameplayGateway.kt:190`).

**15.2 Row audience (`viewers`): REMOVED.** `viewers: all | owner-hidden` (se-viewers 30367149a, a07437204; `RowViewers.kt`, `DisplayOpSpec` /
`FrameOpSpec` / `RigOpSpec`, Paper hide in `PaperPlayerPresentation.kt:185`) hid a display from its own caster and made a rig `owner-hidden` by default.
Founder 2026-10-31: 「術者本人にだけ隠すのそれは要らない気もするけどな。私はいらない。そもそもその設定自体が」. Consequences, all decided:
(1) the rev4.1 rejection of a per-effect `viewers` / receiver policy in Open decisions STANDS again; there is no per-viewer state in the engine;
(2) the rig mesh is visible to everyone, the caster included (the `RigOpSpec.defaultViewers()` default and the particle-row `viewers` of
wp/se-clutter22 are dropped); (3) caster-eye clutter is solved by authoring (visuals that do not sit on the camera) plus the load-time check 16
(`/tmp/claude-1000/skill-engine/design/engine-gaps-v2.md` section 1; see 15.6), not by hiding rows. Status of the code: at b5a81b4a1 the field is
still in the engine; its removal (decoders, `RowViewers`, Paper hide, `DisplayViewersScenarioTest`, the `viewers:` rows in bastion / couture
content, the schema) is a separate code item and this ADR describes the target. Until that lands a `viewers:` key in content is a bug to delete,
not a feature to use. History: `others-only` was drafted and deleted (a07437204) before this removal.

**15.3 Dash clearance and `pass_through`.** `dash` never ends with the caster inside another body: `runtime/ops/DashClearance.kt` grows each body
hitbox by the caster box (`CASTER_HALF_WIDTH` 0.3, the caster height) into a forbidden feet box and resolves the step. A plain dash stops `GAP` (0.02)
in front of the first forbidden box on its way and ends there (`frontEdge`). `pass_through: true` (`DashOpSpec.kt:53,96`, bound in
`TablePlayer.kt:1825`) runs on and rests `GAP` behind the body (`behind`); if no clear spot exists, or `stop_on_block` meets a solid block while the
way out is inside a body, the driver records a `PlayerFault` and finishes (`runtime/ops/DashDriver.kt:16-18,100-130`). Bodies come from
`query.bodiesNear` (Paper: `PaperSkillEntityQuery.kt:44-54`, the live `boundingBox`). Scenario: `DashClearanceScenarioTest`. Shipped:
`pass_through: true` on `content/skills/katana-iaido/skill.yaml:44` and `iron-blade-lunge/skill.yaml:44`; `spear-charge` stops at the front edge.
Consequence for authors: every existing dash (couture s1/s2, bastion s1/s2/ego) now ends at the front edge of a target in the way, not inside it
(founder-check pending). Limit: the geometry is runtime-dependent and proven in game-sim only; the Paper `boundingBox` versus sim `HitBox` difference
is not covered by an e2e row (audit-engine-v3 G3). Manager decision 2026-10-31 (se-iaido 7e5da9327).

**15.4 Weapon hold table.** Founder 10/30: 「移動阻害は立派な能力というか効果なのであくまで武器特性であることを忘れずに」 (a hold is a weapon trait, not
a per-skill add-on). Founder 10/31 answers to the proposal `hold-table-proposal.md`: Q2 = A (bastion-greatsword-s1 loses its `hit_stop`; hit-stop is for
heavy skills only), Q3 = A (a hold status is always applied: no `chance`, the strength of a hold is its `for:` window), Q4 = A (couture-rapier-s2 pins
a hit victim with a short `root`), Q5 = A (the player is shown the trait). Q1 was decided by the manager (2026-10-31): the reserved rows of the
eight deleted Limbus families live only in a memo; skill-less items say "none". Shipped in `ee88df0b6`: `content/status/root.yaml` (`kind: paralysis`,
`params.type: disable`, default 10 ticks, not stackable; locks movement only, attacks and turning stay possible), applied on a needle hit as
`{op: status, status: root, for: "= root_ticks"}` (`root_ticks` default 9, `content/skills/couture-rapier-s2/skill.yaml`); `frost-shard`
`freeze_ticks` 60 -> 40 with the 70% gate removed; `lightning-bolt` / `shock-wand-*` / `chain-lightning` / `frost-rod-sleet` without their `chance`;
`StatusOpSpec.kt:70-90` makes a hold status (`freeze`, `shock`, `root`, `slow`, `sinking`, `stun`) gated by `rand()` / `roll()` in `when:` a LOAD
ERROR (`holdChanceError`). The status icon and the codex slot are `tools/resource-pack/.../StatusEffectArt.kt` and `content/gui/status-codex.yaml`
(index 11); the HUD glyph list is `StatusEffectHudGlyphs.kt`. Guard scenario: `WeaponHoldTableScenarioTest` (no hold status carries chance; each hold
family keeps only its table kind). Shipped families (checked by that scenario): couture s2 roots, frost-shard and frost-rod-sleet freeze, lightning-bolt and shock-wand-zap shock. The per-weapon proposal table is `/tmp/claude-1000/skill-engine/hold-table-proposal.md` (a memo, not part of the engine). In progress (not
in b5a81b4a1): the structured hold row in `core/feature-gameplay/.../service/item/ItemTooltip.kt` (manager decision 2026-10-31: a generated tooltip
row, not a hand-written lore line).

**15.5 Callee-only guard.** A skill with `frames_in` or an unvalued `required` param can only be reached through a caller's `use:`
(`SkillDef.isCalleeOnly`, `kernel/domain/.../skillengine/model/SkillDef.kt:63`). Casting it directly used to do nothing silently (frost-shard-burst).
Now `TablePlayer.start` returns `CastError.CalleeOnly` (`TablePlayer.kt:170`, `runtime/CastRunner.kt:42`); the admin path refuses it up front naming the
callers (`CastSkillError.CalleeOnly` in `CastSkillUseCase.kt:258`, `AdminSkillCaster.kt:30`, `AdminSkillsPanel.kt:130`); the cast list built in
`CombatWiring.kt:701` skips callee-only skills; vfx-shot capture refuses one. Scenario: `CalleeOnlyGuardScenarioTest`. Manager decision 2026-10-31
(01f761b83, 143c1d7b0). Open: moving the check into load so a callee-only skill never appears in a cast list at all is a possible refinement.

**15.6 Caster-eye clutter survival model.** A skill must not fill the caster's own first-person screen. `CasterEyeClutter`
(`tools/game-sim/src/test/kotlin/com/astarworks/astera/gamesim/skillengine/CasterEyeClutter.kt`) reads the recorded oracle: a forward cone
(`HALF_ANGLE_DEG` 70, `RADIUS` 1.5 from the eye, nothing below eye - `FLOOR_BELOW_EYE` 0.9) may hold at most `LIMIT` 10 lingering vanilla particles
(mean client lifetime >= `LINGER_TICKS` 6; lifetimes transcribed from the 26.3 client jar, listed in the file header; a content particle id resolves
to its `vanilla_particle`; each particle def row counts its authored `count`), and block / item displays inside `DISPLAY_RANGE` 3.0 may not exceed a
coverage sum of `DISPLAY_COVERAGE_LIMIT` 1.5 for `DISPLAY_SUSTAIN_TICKS` 4 consecutive ticks. The `viewers=owner-hidden` skip in the current file goes away with 15.2. Called from `ShippedV3ContentScenarioTest`, `CasterRigNearEyeScenarioTest`, `DisplayViewersScenarioTest`. The rule is a test at b5a81b4a1, so it protects shipped content only. Founder 2026-10-31 (see 15.2) decided the cure: the same model becomes load
check 16 (`CasterEyeClutterCheck`), run on a dry cast of the compiled table through the real `TablePlayer` against null gateways and a static
live-value source at a reference pose (design `engine-gaps-v2.md` section 1.2: one engine path, no second model of the laws; the game-sim recorder
becomes a consumer of the same code). A cluttering skill is then refused at load and at hot reload, and `viewers` is not needed. Not yet implemented.
Stricter rule `245bb544a` flagged 22 skills; 20 were fixed by content (81b60f73a); the bastion s1/s2 rows must be fixed by authoring, since
`owner-hidden` is gone.

**15.7 Decision ledger (rev4.22).** Quoted, dated founder decisions: absorbed hit = not hit (10/29, rev4.10); `victim.center` = hitbox centre (10/29,
rev4.9); right-hand rule rotation (10/29, rev4.10 H2); random is visual only, never schedule / timing / end (10/29; `sample_ticks` deleted
2026-10-30); Limbus keeps bastion + couture (10/29); hold is a weapon trait (10/30) and hold Q2-Q5 = A (10/31). Founder answers of 2026-10-31:

| Topic | Decision | Quote | Code state |
|---|---|---|---|
| `viewers` | removed; clutter by visuals + load check 16; rig visible to all | 「術者本人にだけ隠すのそれは要らない気もするけどな。私はいらない。そもそもその設定自体が」 | still in the engine at b5a81b4a1; removal pending (15.2) |
| `billboard` | approved display mode, `sprite` / `text` only, no rotation law, no translation (`engine-gaps-v2.md` section 4: static check `billboard needs kind sprite|text`, ban with `spin` / `align`, existing `translation` ban kept) | founder approval 2026-10-31 (answer to audit Q-A; no separate quote recorded) | the three rules are not yet all enforced (the current decoder bans only translation); pending |
| `sample-*` skills (12) | deleted | 「消す」 | deletion pending |
| patch | both: per-player skin selection (`ISkillSkinRepository`, `SkillVariantResolver`, check 14 skin gameplay parity) and a global balance overlay (`patchKind: balance`, no `variant`, no code) | 「3かな」 | design `engine-gaps-v2.md` section 2; overlay already works, skin selection not implemented |
| hot reload | baseline requirement: content (and plugin jars) change without a server stop | 「そもそも鯖を止めずにできるは当たり前では？ktのjarですらそうあるべき」 | skill reload exists (`astera skill reload`, e2e `reloadOneSkillChangesNextCastOnly`); a Kotlin jar swap without stop is NOT built (open) |

Manager decisions (no founder quote): Q1 of the hold table, callee guard, dash clearance, tooltip hold row (all above). Still open for the founder:
which three skills the expressiveness trial builds (audit Q-E). Q6 stays closed: the handler `at: 1` form (rev4.21); the handoff lists it as
answered "A".

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
- Breaking change to every skill written before rev4 and to the presentation half of 0301 (accepted: pre-release posture, no shims).
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
  table (C1); the client's `billboard` is the only per-viewer facing, and it is a display field, not a frame (`billboard` is approved for `sprite` / `text` displays only, with no rotation law and no translation, 2026-10-31, 15.7). Edge-travel wording for swings
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

1. **Held-item context, RC3.** DECIDED (rev4.21): an authored item display defaults to `fixed` (`DisplayOpSpec.kt:163`); the rig mesh of the held main-hand / off-hand item is drawn `THIRDPERSON_RIGHTHAND` / `THIRDPERSON_LEFTHAND` from the cast-start item (`TablePlayer.kt` rig spawn, `PaperSkillVisualGateway.kt`). Original options, kept for the record: `fixed` vs `thirdperson_righthand`. `content/frames/couture-rapier.yaml` points are measured in the
   `fixed` basis and the three pieces of coin 2 share it; `thirdperson_righthand` (`held_hand` part in the frames file) matches how the real held
   weapon looks and avoids a pop when the real item is hidden. Needs a capture of both before the first skill ships.
2. **Axis sign convention.** RESOLVED (rev4.10 / H2): the right-hand rule, worded as the cyclic sentence forward -> left -> up -> forward (section 0);
   a convention with the opposite sign is not used. Name-keyed offsets make the offset side a non-issue.
3. **Sprite authoring unit**: DECIDED, blocks (`size` is "[w, h] in BLOCKS", `DisplayOpSpec.kt:179`).
4. `ribbon`/`trail` internals: DECIDED and specified (trail `of/root/history/width|width_frac/fade/style`, line `from/to/segments (1..24)/jitter`, `RibbonOpSpec.kt:111-123`; rev4.14 / rev4.15, section 5).

**Founder questions Q1-Q7 (rev4.3).** All seven are RESOLVED (Q6 in rev4.21).

| Q | topic | option A | option B | where |
|---|---|---|---|---|
| Q1 | `max_count` on `hit` / area `damage` | required, an integer or `all` | optional, omitted = unlimited | 10.1b. **RESOLVED B (founder 10/29)** |
| Q2 | `victim.body` height | live hitbox centre (0.9 for a standing player) | fixed `victim.chest` at feet + 0.8 | 1. **RESOLVED (founder 10/29):** neither; the point is `victim.center` = (feet + eye) / 2, live |
| Q3 | `gameplay.expose` | drop it | keep a `kind: Patch` form for later exposure | 13. **RESOLVED A (founder 10/29)** |
| Q4 | hit `damage` optional | omit on the hit; a handler `damage` deals it (v2 order) | always required on the hit; accept the order difference | 10.1b. **RESOLVED A (founder 10/29)** |
| Q5 | couture `absorb` (example e) | **RESOLVED A (founder 10/29):** transition `absorb: true` | `modify_attribute resist.physical` | example e (also option C: a `parry {for, scale: 0}` op) |
| Q6 | coin1 delay | **RESOLVED A (engine fact, 2026-10-30):** handler `at: 1`; shipped as `{id: c1_damage, op: damage, at: 1, ...}` in `on.hit` (`content/skills/couture-rapier-s2/skill.yaml:129`) | (was: `hit` gets `damage_delay: 1`; no such field exists, `HitOpSpec.kt` has none) | example a / g |
| Q7 | screen ops | **RESOLVED 2026-10-29: A.** `screen_flash` / `screen_veil` revived as presentation ops (10.1b, rev4.7) | (was: drop them) | 10.1b |

## Skin rig (SKL-17)

A held-object performance is one `rig` op, not a `body` clip (formerly `caster_anim`) plus a static hand point. `rig` plays a skin-rig clip (`anim`) as a tree of frames: the
body, arm and hand bones are frames carrying the clip's per-tick motion; the skin displays (`spawnSkinBone`) ride them through `toDisplayWrite`; the
held `mesh` is a child of the driven frame (origin = arm position + arm rotation * (grip offset + pivot), rotation = arm * tilt *
pose * rest, scale 0.9375). The real body is hidden under a `BODY` lease for the duration. The pose is a rotation vector computed per tick from the
`swing` steps; the runtime holds no angles in degrees or Euler triples. Anything the rig op cannot say (a
swapped model, `ghost_bodies`, block parts without the block op) is a load error, never dropped.

The v2 tilt seen on dev (2026-10-28) came from `main_hand` hanging off a static hand point at scale 1.0 while `caster_anim`
animated the arm outside the engine; the held mesh hangs from the animated mount. Rider displays never teleport (teleport 0, interpolation N), as in v1.

(rev4.13) The held mesh is `AnchorHeldItem` of the cast-start item (section 1), so the hand state after the cast started is irrelevant. The rig's points carry `PLAYER_SCALE` (0.9375) so `rig.grip` / `rig.tip` sit on the drawn mesh; and when the rig freezes (`respect_hit_stop` windows, `freezes`), the body clip is told the same stand-still windows as `SkinRigHold(at, ticks)` (in unheld clock ticks) at `play`, so the arm and the sword stop and resume together. `hand: off` (integrate10, `RigOpSpec`) draws the off-hand part's own pack model in `THIRDPERSON_LEFTHAND` on the left arm.

The rig has no `item` field: it is the skill's `item:`, and `part?` names a part of `content/frames/<item>.yaml`. A `rig` op under `item: any`, or in a skill
with no item, is a load error (`rig needs a concrete item`).

Rig `parts` are `block` ops. v1 hangs them from the anchor pivot and turns them by the swing pose alone (`VfxRigPlanner.partPlan`: `pivotWorld + anchorRot * R(t) * offset`, no arm), so a mounted rig gets a second, anchor-parented `rig` frame (`<rig>_parts`, no skin bones, no `mount`) that carries the parts; the held mesh stays on the driven frame.

## References

- 関連 ADR: [[adr/0301-skill-engine-v2-frame-tree]], [[adr/0290-generic-vfx-primitives]], [[adr/0276-skill-performance-one-body-one-weapon-one-lease]] (v1 passenger transport), [[adr/0239-custom-particle-sprites-as-textdisplay-glyphs]]
- Audit: `/tmp/claude-1000/skill-engine/audit/root-causes.md`, `/tmp/claude-1000/skill-engine/semantic-diff.md`
- v1: `d41b82f1e`; stream/plate patch input: `wp/se-streak` `bea1b2641`
