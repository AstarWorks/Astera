---
status: Proposed
date: 2026-05-20
deciders: Astera workspace (Wave _skills-2)
supersedes: null
superseded_by: null
---

# ADR-0077: Mechanic-Targeter-Phase skill architecture + CEL injection

## Status

Proposed

## Context

The Wave _skills-1 attempt at a "composable skill effect tree" (intended
ADR-0074, never committed to HEAD due to a staging race in commit
`48d95ba`) was shallow:

- **4 fixed hooks** (`on_hit` / `on_miss` / `on_expire` / `on_cast_complete`)
  cannot capture a gun-class lifecycle (reload-start / reload-tick / fire /
  in-flight-trail / hit-block / hit-entity / miss / expire).
- **6 effect types** (SpawnParticle / PlaySound / SpawnDisplay / ApplyDamage /
  ApplyStatus / Knockback / CastSkill) — too few. MythicMobs ships ~200
  mechanics; even RTM's `SimpleShoot` alone has 3 phases plus a per-tick
  callback we cannot represent.
- **No targeter**: every hook implicitly targets "the hit entity"; you cannot
  damage the target while particle-spawning at hit-location while
  knock-backing nearby entities, all from one event.
- **No compound damage**: `DamageProfile(base, attribute)` — single attribute.
  PvP balance routinely wants `8 physical + 3 fire`.
- **No dynamic value**: every `Double` is literal. CEL is locked at the port
  layer (ADR-0035) but never wired into mechanics.

Reference comparison (RTM master @ `45e5977`, MythicMobs public docs):

| Aspect | RTM | MythicMobs | Wave _skills-1 (intended) |
|---|---|---|---|
| Mechanics | 1 class (SimpleShoot) | ~200 | 6 |
| Targeters | none (implicit) | ~50 | none |
| Per-tick callback | yes (string event "Projectile") | yes | no |
| Compound damage | no (empty enums) | yes | no |
| CEL / scripting | RTM issue #21 (planned) | placeholders + conditions | port only |
| Phase per archetype | string-keyed (3 events) | implicit | 4 fixed hooks |

User judgment: "そう考えると MythicMobs は優れているのかもしれない" →
correct. Astera ports MythicMobs philosophy to typed Kotlin sealed types.

## Decision

The Skill side of Astera is rebuilt around three sealed surfaces and one
expression injection point.

### 1. `Mechanic` — what to do (sealed, ~35 variants)

Each `Mechanic` is a declarative, self-contained "one line of action in
YAML". Carries an explicit `targets: Targeter` plus its own params. Timing
fields (`delayTicks` / `periodTicks` / `durationTicks` / `condition`) are
inherent on every mechanic — there is **no separate `Delay` / `Repeat` /
`Tick` mechanic**. The scheduler (RTM `TaskUnit`-style) folds timing into
its task model.

Categories (all sealed under `core.domain.skill.mechanic.Mechanic`):

- Gameplay mutation: `Damage`, `Heal`, `Resurrect`
- Status: `ApplyStatus`, `RemoveStatus`, `Cleanse`, `Ignite`, `Freeze`,
  `Silence`
- Movement: `Knockback`, `Knockup`, `SetVelocity`, `Propel`, `Teleport`,
  `Leap`
- World: `SummonLightning`, `Summon`, `SetBlock`, `RemoveBlock`, `Explode`
- Item / inventory: `GiveItem`, `ConsumeItem`, `ModifyAttribute`,
  `SetCooldown`
- Visual / cosmetic: `SpawnParticle`, `ScheduleParticleAnimation`,
  `PlaySound`, `SpawnDisplay`, `Glow`, `CameraShake`
- Messages: `SendMessage`, `SendActionBar`, `SendTitle`
- Control flow (structural): `CastSkill`, `IfThen`, `Loop`, `Choose`

`CastSkill` recursively invokes another skill by id. `IfThen` carries a
`Condition`. `Loop` is synchronous iteration. `Choose` is weighted random
branch.

### 2. `Targeter` — who / where (sealed, ~15 variants)

```
Caster | Target | Origin | HitLocation | ProjectilePosition
CasterOffset(VectorValue) | TargetOffset(VectorValue) |
ProjectileOffset(VectorValue) | HitOffset(VectorValue)
NearbyEntities(center, radius, maxCount, excludeCaster)
ForwardRay(from, distance) | Cone(from, direction, angle, distance)
Enemies(center) | Allies(center, includeSelf)
Filter(base, Condition) | Random(from, count)
```

Targeters compose: `Filter(NearbyEntities(Target, radius=5), HealthBelow(0.3))`
is "low-HP enemies within 5 blocks of the target".

### 3. `PhaseMechanics` — archetype-keyed event slots (sealed per archetype)

Phase is archetype-semantic. Per-tick semantics are NOT phases — they live
in `Mechanic.periodTicks`. Phase enum per archetype:

- `Projectile`: cast, hitEntity, hitBlock, miss, expire (5)
- `Melee`: cast, hitEntity, miss (3)
- `Aoe`: cast, hitEntity, expire (3)
- `Buff`: cast, expire, removed (3)
- `Teleport`: cast, preTeleport, postTeleport (3)
- `Summon`: cast, spawn, summonDeath (3)

Future `Gun` archetype reserved (`reloadStart` / `reloadTick` /
`reloadComplete` / `outOfAmmo` etc) — not landed in this wave.

### 4. `NumericValue` / `IntValue` / `VectorValue` / `BoolValue` — CEL injection

```kotlin
sealed class NumericValue {
    data class Literal(val value: Double) : NumericValue()
    data class Cel(val script: String, val refs: List<BindingRef> = emptyList()) : NumericValue()
}
```

Same shape for Int, Vec3, Boolean. Every numeric / boolean / vector field
on any `Mechanic` or `Targeter` or `Condition` becomes one of these.

Runtime evaluation: `Literal` returns directly. `Cel` calls
`ICelEvaluator.evaluate(script, ctx)`. Phase 1 uses `StubCelEvaluator`
(returns `Result.Failure(NotYetImplemented)`); the dispatcher catches and
skips the affected mechanic with a warn-log. Phase 6 lands a real CEL
evaluator (JCEL or DIY), at which point `cel:`-tagged YAML automatically
activates.

### 5. `DamageComponent` — compound damage

```
DamageProfile = List<DamageComponent>
DamageComponent(base: NumericValue, attribute: DamageAttribute, multiplier: NumericValue = 1.0)
```

`SkillParams` damage fields (Melee / Projectile / Aoe) become
`List<DamageComponent>`. Multiple attributes per skill, each independently
runs through element-matrix.

### 6. `Condition` — boolean predicates for `IfThen` / `Filter`

`Cel` / `HasStatus` / `IsAlly` / `IsEnemy` / `HealthBelow` /
`DistanceLessThan` / `And` / `Or` / `Not`. Composable.

### 7. Binding-side decoration: `phase_overrides`

`ItemSkillBinding.hookOverrides` (Wave _items-1) renamed to `phaseOverrides`
+ archetype-aware. Per-phase `_append` / `_prepend` / `_replace` of
mechanic lists.

## Consequences

### Positive

- Author can write a 30+ mechanic list across 5-6 phases, with explicit
  targeter, compound damage, CEL-injected values, and per-mechanic timing
  — matches MythicMobs depth.
- Sealed type discipline (Mechanic / Targeter / Condition / *Value) ensures
  Konsist + JSON Schema + VSCode autocomplete fully cover the surface.
- Phase 6 Paper-side adapter and Phase 6 CEL evaluator both have clear port
  shapes already locked.
- Forward-only redesign — no legacy compatibility shims.

### Negative / Trade-offs

- **150-200 file mega-wave** (Wave _items-1 scale). Adds 35 mechanic types,
  15 targeter types, 10 condition types, 4 value sealed types, 6 phase
  enums + 6 PhaseMechanics data classes, MechanicDispatcher (35+ branch),
  6 archetype handler rewrites.
- **YAML verbosity**: explicit `targets: { kind: target }` etc., relies on
  VSCode + schema autocomplete to keep author UX tolerable. Already wired
  in Wave _items-2b (ADR-0076).
- **CEL evaluator stub during Phase 1**: any `cel:` field warns + skips
  until Phase 6.
- **MythicMobs depth not reached**: Astera ships ~35 mechanic types vs ~200.
  Coverage focus: PvP gameplay (damage / status / movement / visuals /
  messages). Crafting / world / mob-AI mechanics deferred.
- **Wave _skills-1 intended design (4 hooks + 6 effects, ADR-0074 never
  committed) is supersed without ever landing**. Acceptable per CLAUDE.md
  §Pre-release posture.

### Mitigations

- Worker territories carefully partitioned (value/condition/targeter vs
  mechanic/phase/spec vs YAML DTO vs dispatcher/content) to enable parallel
  4-worker Phase B.
- `SkillCycleValidator` is recursive visitor — handles CastSkill nested in
  IfThen / Loop / Choose.
- Schema regen + `.vscode/settings.json` saturation already done in ADR-0076.
- ADR-0035 CEL port + StubCelEvaluator already in place — no port redesign
  needed.

## Alternatives Considered

- **Keep Wave _skills-1 design**: too shallow per design critique above.
- **MythicMobs verbatim** (~200 mechanic): out of scope for Phase 1 PoC
  closing. 80% coverage with ~35 is acceptable.
- **String-keyed mechanics** (RTM-style `effect.particles["Projectile"]`):
  loses type safety, doesn't fit Kotlin sealed-type discipline.
- **Per-archetype phase as single enum** (vs sealed per-archetype):
  string-keying again loses type safety. Sealed per-archetype keeps
  exhaustive `when` checks.
- **Phase-level periodTicks vs mechanic-level**: chose mechanic-level so
  any individual mechanic in a phase can have its own timing without
  needing nested wrapper mechanics. RTM's `TaskUnit(delay, period,
  condition, task)` pattern.

## References

- ADR-0073: Item-centric content model + multi-skill binding (Item side
  unchanged)
- ADR-0035: Marketplace + JP currency + CEL (CEL port + StubCelEvaluator
  reused here)
- ADR-0076: Schema wiring + legacy color (VSCode autocomplete is what
  makes the verbose YAML tolerable)
- ADR-0030: Combat depth + damage event decomposition (DamageRule pipeline
  reused for compound damage)
- RTM source: `dev.ryuzu.ryuzutechnicalmagic.core.impl.model.skill.weapon.
  SimpleShoot` (per-skill ParticleScheduler + TaskUnit timing model)
- MythicMobs public docs (mechanic / targeter / condition vocabulary)

## Future extension points (not in this wave)

- **Wave 4 (separate ADR)**: Particle scheduler + time-animated patterns
  (`AnimatedShape` sealed, `IParticleAnimator` port, nested animations).
  Layered on top of `Mechanic.ScheduleParticleAnimation`.
- **Phase 6 (explicitly excluded by user)**: Paper-side adapter
  (`IWorldGateway.spawn*` Paper impl, `IParticleAnimator` Paper impl,
  `ICelEvaluator` real impl).
- **`Gun` archetype**: ammo / reload / spread / recoil. Phase 2 + likely
  a separate ADR.
- **Free-standing skill modifiers** (Option C from prior plan): passive /
  rune / set bonus that decorate skills by tag match. Phase 2.
