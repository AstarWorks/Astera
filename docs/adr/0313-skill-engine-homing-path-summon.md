---
status: Accepted
date: 2026-10-31
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0313: Skill engine primitives for homing, curved paths and summons

## Status

Accepted. Extends [[adr/0303-natural-skill-schema]] (does not change it).

## Context

Founder 2026-10-31: whatever the weapon (melee, thrown, magic, bow), is the engine "simple and still expressive"? Three common weapon shapes could not be written in YAML alone: a bolt that bends toward an enemy, a lob that follows a curve, and a turret or minion that fights on its own. All three must stay inside the precompute contract of ADR-0303 (schedule fixed at load, random for visuals only) and must go through the one `TablePlayer` path so sim and Paper cannot diverge.

## Decision

Three primitives, no new runtime clock and no new randomness.

**Projectile `homing: {target: enemy, turn_deg_per_tick, acquire}`.** Each tick the body's heading turns toward the target by at most `turn_deg_per_tick` (a great-circle step), then the body advances by speed along the new heading. The target is the nearest enemy hitbox centre within `acquire` blocks of the body, re-picked every tick (the lock holds no state); ties break by x, y, z so list order never matters. With nobody in reach the body flies straight on its current heading. `turn_deg_per_tick` is in (0, 180], `acquire` > 0, `target` is `enemy` only. A homing body needs a world origin (a builtin or document anchor, not a frame instance). The body's display rotation is not turned to the heading (the projectile display keeps its authored orientation). Loaded `look` keys (`homing.*`) are written only when `homing` is set, so every existing golden is byte-identical. Bounce (`rebase`) keeps the steering.

**Path law `{law: path, points: [...], at, for, ease}`** for frames and displays. Points are frame-relative (`{forward, up, left}`). The curve is a uniform Catmull-Rom spline through the points with end tangents from reflected neighbours; two points are a straight line. Forward equals the curve tangent (pose key derived from forward-to-tangent). It lowers at load to one absolute key per tick (offset and pose), the same table a `keys` law gives, so the runtime and the segmenter are unchanged and the position at an integer tick is exact. `ease` shapes progress along the curve. A billboarded display rejects it (a path turns the body about axes a billboard cannot).

**`summon` op `{id, op: summon, at, skill: <id>, anchor, life}`.** Starts an independent run of another skill: its own machine, clock and seed (derived from the summoner's seed, the tick and a per-run counter, never random). The callee declares exactly one `frames_in` frame, which is seeded at the spawn point facing the caster's heading, and no unvalued required param, anchors, fragments or conditions. The run is owned by the caster (damage credit, `ownerOf`), starts next tick, outlives the summoning cast, and is cut like a cancelled cast `life` ticks after it starts (1..1200). Cleanup: `life` elapsed, the caster's death/quit/world change (`CastRunner.cancelAllOf`), or match end (`cancelAll`). At most 8 live summons per caster; the ninth is a counted `LIMIT` fault, not a silent skip. The duration key is `life`, because `lasts` is a timing key of the op grammar. Platform wiring: `TablePlayer.summonSkills` resolves ids from the skill catalog (set in `SkillEnginePaperRuntime` and `SimSkillEngine`). Display and frame only; the hitbox of a summon is left open (below).

## Consequences

### Positive
- Homing bolts, lobs and turrets are YAML: `content/skills/{homing-orb,arc-lob,sentry-totem,sentry-turret}`.
- One player path for sim and Paper; no golden changes outside the new skills.

### Negative / Trade-offs
- Homing re-picks every tick, so two near enemies can flip the lock; the tie-break keeps it deterministic, not stable.
- The shipped-content harness skips the single-run table oracle for skills that summon, since a summon is a second run; `SummonHomingPathScenarioTest` covers it.

### Mitigations
- Faults are counted and strict at load for homing, path and summon misuse.

## Alternatives Considered

- **Bezier path**: needs control points that are not on the curve; Catmull-Rom passes through authored points, which is easier to author.
- **Sticky homing lock**: needs per-body state; stateless re-pick is simpler and deterministic.
- **Summon as a `use: skill:` call**: a call shares the caller's clock and ends with it; a turret must outlive the cast.

## Open question

Resolved by rev 2026-10-31 (below). Still open: whether an ally can heal or buff a summon body (today an ally's hit changes nothing, and no status or heal lands on a body). Out of scope here.

## Rev 2026-10-31: per-summon options (founder: 「召喚物は普通に場合による。死んでも残るやつ、倒せる奴、HPあるやつetc...」)

The hitbox question is closed: whether a summon outlives its caster, can be killed and has hp is a per-skill choice on the `summon` op, not an engine default. Two optional fields, both absent = today's behaviour (goldens of every skill that does not set them are byte-identical; the dump prints them only when set).

- `outlives_caster: true|false` (literal, default false). True: `CastRunner.cancelAllOf` (death, quit, weapon swap, world change) skips the run; `life`, a broken body and `cancelAll` (match end, reload) still cut it.
- `body: {hp, width, height}` (`hp` > 0 required, `width` 0.8 and `height` 1.6 by default). The kernel keeps a registry of live bodies owned by the `TablePlayer`: a synthetic UUID, the owner, hp, and a box standing on the spawn point. Every `ISkillEntityQuery` call and every `ISkillGameplayGateway.damage` of every run goes through two decorators, so enemy hits, damage ops, projectiles and ray/area queries see the body like any other victim. Teams are the existing rules: `isAlly` maps a body to its owner, so the owner, the owner's team and the owner's own area ops pass over it, and a hit from an ally (or the owner) leaves hp unchanged; an enemy's damage lowers hp. Status, heal, impulse and freeze on a body id are no-ops. At hp <= 0 the body is removed and `damage` reports a kill.
- `on: destroyed`: a body that reaches 0 hp raises the event `destroyed` on its run (a `GLOBAL_EVENTS` name). A summoned skill may handle it like any event (`on: [{event: destroyed, to: broken}]`) and play a break state; a run with no handler is cut at once. A summoned skill that handles `destroyed` while the summon op gives no `body` is a load error.
- Paper: the new port `ISkillBodyGateway` (`TablePlayer.bodyGateway`) spawns a non-living, non-persistent `Interaction` entity per body (`PaperSkillBodyGateway`), so vanilla melee and arrows have something to hit while the living-entity query never sees it twice. Its damage events are cancelled and routed to `TablePlayer.damageBody`. The entity is removed exactly once when the run is finalized for any reason (life, break, `cancelAll` on reload / match end, cancel), tagged for a start-up sweep, and removed again by `removeAll`. The sim uses the default `NoBodyGateway` (kernel queries only).
- Examples: `sentry-totem` summons `sentry-turret` with `outlives_caster: true, body: {hp: 20}`; the turret handles `destroyed` with a `broken` state. Scenarios: `SummonBodyScenarioTest`.

## References

- [[adr/0303-natural-skill-schema]]
- `tools/game-sim/src/test/kotlin/com/astarworks/astera/gamesim/skillengine/SummonHomingPathScenarioTest.kt`
