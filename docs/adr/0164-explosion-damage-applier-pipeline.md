---
status: Accepted
date: 2026-06-03
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0164: Wave 6e-N1 — ExplosionDamageApplier real impl

## Status

Accepted. Sub-wave follow-up to Wave 6e (ADR-0162). Wires
`PaperExplosionGateway`'s `ExplosionDamageApplier` SPI to
`ApplyDamageUseCase` so explosion entity damage flows through the full
Astera modifier pipeline (armor reduction, critical strike, friendly
fire, combo bonuses, focus-fire bonuses).

## Context

Wave 6e (ADR-0162 §A "PaperExplosionGateway") shipped the Paper adapter
wired with `ExplosionDamageApplier.None` — the identity applier. The
ADR explicitly noted the follow-up: "a 1-day Wave 6f follow-up that
requires a small adapter class to fit `ExplosionDamageApplier` (per-target
call) over `ApplyDamageUseCase` (returns Result)." Without this binding,
PaperExplosionGateway visually fires explosions and broadcasts
`ExplosionTriggered` events but applies zero damage to entities — vanilla
fall damage from the blast might apply, but the canonical Astera-owned
damage pipeline is not engaged.

`ApplyDamageExplosionDamageApplier` is that adapter.

## Decision

### Adapter shape

`plugin/combat/.../application/service/aoe/ApplyDamageExplosionDamageApplier.kt`
(~90 LOC including KDoc). Wraps `ApplyDamageUseCase`. Implements
`ExplosionDamageApplier`:

```kotlin
override fun apply(target: PlayerId, baseDamage: Double, spec: ExplosionSpec): Double {
    // 1. Synthesize a single-component DamageProfile from baseDamage +
    //    primary attribute of spec.damageProfile.components.first()
    // 2. Build DamageAttempt(targetId=target, baseProfile=…, source=spec.source, headshot=false)
    // 3. applyDamageUseCase.execute(attempt) → side effect on success
    // 4. Return baseDamage on Success, 0.0 on Failure (log WARN)
}
```

### Profile synthesis rationale

`PaperExplosionGateway` calls `damageApplier.apply(target, falloffedDamage, spec)`
where `falloffedDamage = baseDamage * (1 - distance / radius)`. Reusing
`spec.damageProfile` directly would lose the falloff; rebuilding with
`NumericValue.Literal(baseDamage)` preserves the explosion's attribute
mix (Fire / Earth / Physical / …) while carrying per-target falloff
magnitude. The primary component's attribute + multiplier are
preserved; multi-component profiles collapse to a single component on
this path until the Phase 6 pipeline iterates every component.

### Return value semantics

`DamageOutcome` does not expose the actually-applied post-pipeline
amount. The adapter returns `baseDamage` (the pre-pipeline input) for
the `ExplosionResult.affectedPlayers` row — informational, not
authoritative. Downstream consumers needing exact post-pipeline damage
subscribe to `DamageDealt` events (published by ApplyDamageUseCase),
not `ExplosionTriggered`.

### Failure handling

`ApplyDamageUseCase.execute` can return `Result.failure` (target immune,
gateway error). The adapter logs DEBUG (not WARN — immunity is a normal
gameplay state) and returns 0.0. `ExplosionResult.affectedPlayers`
honestly reports zero applied damage for that target.

### Phase C wiring

`AsteraCombatPlugin.onEnable` ordering shift:

- Wave 6e shipped: PaperExplosionGateway registered at line ~246
  immediately after `InMemoryExplosionGateway`, with
  `damageApplier = ExplosionDamageApplier.None`.
- Wave 6e-N1: that priority-100 PaperExplosionGateway registration is
  **deferred** to after `ApplyDamageUseCase` is constructed (around
  line 470 in the existing CombatPlugin layout). The deferred
  registration uses `damageApplier = ApplyDamageExplosionDamageApplier(applyDamage)`.
- `InMemoryExplosionGateway` at priority 0 keeps `.None` — non-Paper
  test harnesses see the historical "identity damage applier" behaviour
  unchanged. Production overlay at priority 100 wins via ServiceRegistry.

## Consequences

### Positive

- Explosion entity damage now applies the same modifier pipeline as
  direct hits. Armor reduction (ADR-0058) takes effect; critical
  strikes / headshots possible; friendly-fire policy (ADR-0063)
  prevents griefing inside a team; combo / focus-fire bonuses
  (ADR-0105) layer on attacker side.
- ExplosionTriggered + DamageDealt events fire correctly. Statistics /
  audit / Discord webhook pipeline consumes both — operators see
  explosion damage in the same canonical surface as direct-hit damage.
- Phase 3 Siege gameplay (TNT carry detonation hitting wall defenders,
  AOE skills splashing) actually functions on a Paper server.

### Negative / Trade-offs

- Single-component DamageProfile collapse. Multi-attribute explosions
  (Fire + Earth in one spec) lose the secondary attribute on this
  path. The InMemory pipeline operates on the primary attribute only
  (per ADR-0058 Phase 1 single-attribute pipeline note), so this isn't
  a regression — but a future multi-attribute pipeline would need to
  iterate `spec.damageProfile.components` here.
- Return value is `baseDamage`, not post-pipeline applied. Consumers
  inspecting `ExplosionResult.affectedPlayers` see the input damage,
  not the armor-reduced output. Documented in adapter KDoc.

### Mitigations

- DamageDealt events carry the exact post-pipeline amount — consumers
  needing that subscribe to events, not to ExplosionResult.

## Alternatives Considered

- **Pass an `IDamageApplier` directly to PaperExplosionGateway**: would
  require changing the port's constructor signature, breaking the
  InMemory-SSOT-mirror contract. Adapter wrapping the SPI is the
  minimal-friction path.
- **Move applyDamage construction earlier in CombatPlugin onEnable**:
  would force `damageGateway / knockbackGateway / statusContainer /
  damageRule / armorProvider` to be resolved earlier too. Domino
  effect; not worth it for one adapter. Defer-explosion-registration
  is the smaller diff.
- **Use `Result.fold` to keep the post-pipeline number**: `DamageOutcome`
  does not carry it. Would require a port change (e.g., `HpReduced(remainingHp, applied)`)
  affecting every consumer of DamageOutcome. Too wide a sweep.

## Out of scope (deferred)

- **Multi-attribute pipeline support**: depends on ADR-0058 / Phase 6
  pipeline rework.
- **InMemoryExplosionGateway also wired to ApplyDamageUseCase**:
  optional. Production routes through priority-100 Paper anyway;
  InMemory stays with `.None` for test simplicity. A future test that
  asserts "InMemory + real applier == Paper + real applier" could wire
  both.

## References

- [[adr/0162-paper-sweep-round-3-explosion-areaeffect]] — Wave 6e parent
  ADR. §"Negative — ExplosionDamageApplier.None default" called out
  this exact follow-up.
- [[adr/0030-combat-depth-and-damage-event-decomposition]] — original
  damage pipeline ADR.
- [[adr/0058-universal-attribute-container]] — armor reduction shape
  applied here.
- [[adr/0063-damage-attribution-window]] — assist + damage-history
  layered through ApplyDamageUseCase, now inherited by explosions.
- [[adr/0105-combat-depth-foundation]] — combo + focus-fire bonus
  sources applied here.
