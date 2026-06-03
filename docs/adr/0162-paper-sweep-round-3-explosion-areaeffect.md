---
status: Accepted
date: 2026-06-03
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0162: Wave 6e — Paper Sweep round 3 (Explosion + AreaEffect)

## Status

Accepted. Wave 6e lands Paper impls for the two largest remaining
combat-side outbound ports: `IExplosionGateway` and `IAreaEffectGateway`
(Wave 5m, ADR-0088). The wave flips 2 RTM rows 🔌 → ✅ and unlocks the
Phase 3 Siege gameplay use cases that depend on explosions (TNT carry-mode
detonation, BreakCore objective destruction) and persistent area effects
(`AreaEffectAction.{ApplyStatusEffect, DealDamage, SpawnParticle}` on a
tick schedule).

## Context

Wave 5m (ADR-0088) shipped the AOE port surface with InMemory defaults
but explicitly deferred the Paper impls to a future wave. Wave 6a
attempted to absorb both impls but ran out of scope budget; Wave 6b/6c
kept them deferred to focus on the smoke-test prerequisite chain.

By Wave 6d's close (ADR-0161) the only large remaining Paper impls are:

- `IExplosionGateway` — `World.createExplosion` + Astera-owned per-target
  damage + per-block destruction routed through `IBlockStateGateway` (which
  in turn write-throughs to the Wave 6c §B journal). ~440 LOC.
- `IAreaEffectGateway` — `IScheduler.runRepeating` tick action dispatch +
  entity-in-shape query + per-action SPI routing
  (`IDamageApplier` / `IStatusEffectApplier` / particle spawn). ~282 LOC.

Both are non-trivial but well-bounded: each has a single InMemory SSOT
that the Paper adapter mirrors 1:1 in behaviour, adding only the Bukkit
side effects (visual blast / per-tick particle emission / entity damage
fan-out). The PoC end-state — "1 weapon fires end-to-end + AOE skill
visually splashes + explosion clears blocks" — was previously achievable
only via InMemory snapshots; with Wave 6e it becomes real on a Paper
server.

## Decision

Wave 6e ships both adapters as one branch / one commit / one ADR, with
two §s:

### §A — PaperExplosionGateway (~440 LOC)

Path: `plugin/combat/src/main/kotlin/com/astarworks/astera/combat/adapter/paper/aoe/PaperExplosionGateway.kt`

Constructor:
`(server, scheduler, eventBus, nearbyQuery, knockbackGateway, blockStateGateway, damageApplier = ExplosionDamageApplier.None, radiusCap = null)`

- **Visual blast**: `World.createExplosion(loc, power, setFire=false, breakBlocks=false)` for vanilla sound + particles + screen shake. Entity damage is ALWAYS suppressed at the Bukkit layer — Astera owns damage per ADR-0149 §C. The vanilla `EntityDamageEvent` that fires alongside the explosion is cancelled by `BukkitEventAdapter` (Wave 6a-completion, ADR-0152).
- **Entity damage**: query `nearbyQuery.entitiesInside(world, center, radius)` and apply per-target falloff damage via the `ExplosionDamageApplier` SPI. The default `ExplosionDamageApplier.None` is wired in Phase C (Wave 6f will add an `ApplyDamageUseCase`-backed adapter that flows damage through the full modifier pipeline — armor / crits / FF / combo / focus-fire). Knockback is applied via the existing `IKnockbackGateway`.
- **Block destruction** per `BlockDamagePolicy`:
  - `None`: skip block-side effects entirely.
  - `Vanilla`: single `world.createExplosion(loc, power, false, true)` call. Vanilla entity damage fires alongside but `BukkitEventAdapter` cancels it. `affectedBlocks` is reported as 0 (uncountable from `createExplosion`'s return shape).
  - `Astera(ratio)`: walk a block radius sphere capped at `radiusCap ?: power` (max 32), call `blockStateGateway.applyDamageFromSkill(world, pos, SkillId("astera:explosion"), attackerId, BlockDamageProfile(...))` for each block. The `ratio * falloff` scales the damage profile's `baseAmount`. Blocks already broken (durability == 0) are skipped.
- Off-main: defer via `scheduler.runLater(Duration.ZERO, ...)` and return `Result.success(EMPTY_RESULT)`. Main-thread runs synchronously and returns the populated result with per-target damage records and affected-block count.
- Publishes `ExplosionTriggered` event mirroring InMemory.

Tests deferred — Bukkit `Server.getWorld` + `World.createExplosion` + `Player.location` + the `Attribute.KNOCKBACK_RESISTANCE` chain are too heavy for `java.lang.reflect.Proxy` doubles. Phase C `./gradlew check` covers compile + Konsist; Wave 6d lightning adapter set the same no-tests precedent.

### §B — PaperAreaEffectGateway (282 LOC)

Path: `plugin/combat/src/main/kotlin/com/astarworks/astera/combat/adapter/paper/aoe/PaperAreaEffectGateway.kt`

Constructor:
`(server, scheduler, eventBus, nearbyQuery, damageApplier, statusApplier, worldGateway)`

- **Spawn**: resolve world, off-main → defer via `scheduler.runLater(Duration.ZERO)` then return handle. World-not-loaded → `Result.failure(AreaEffectError.WorldNotLoaded)`. Generate a UUID handle, register in live-map, schedule the tick action via `scheduler.runRepeating(periodTicks = spec.tickPeriodTicks, ...)`. Publishes `AreaEffectSpawned`.
- **Tick action** dispatched on the sealed `AreaEffectAction`:
  - `ApplyStatusEffect(effectId, durationTicks, intensity)`: query `nearbyQuery.findInRadius(boundingRadius(shape))`, call `statusApplier.apply(playerId, effectId, durationTicks)` per match.
  - `DealDamage(profile)`: same query, route through `damageApplier.apply(DamageAttempt(...))`.
  - `SpawnParticle(particleSpec)`: emit via `worldGateway.spawnParticle(world, position, particleSpec)` — no target query needed.
- **Despawn**: cancel scheduled task, remove from live-map, publish `AreaEffectDespawned(reason = Manual | Expired)`.
- **Bounding radius**: over-approximates Box (half-diagonal) and Ring (outerRadius). Fine-grained shape filtering deferred to a future `IShapedNearbyQuery` port (documented in adapter KDoc).
- **No vanilla AreaEffectCloud**: skipped intentionally — vanilla cloud carries its own particle/duration/potion semantics that would double-emit and leak lifetime out of `IScheduler`. Particle emission is purely `AreaEffectAction.SpawnParticle`-driven.
- **Backend id**: `"paper"`. Cross-impl despawn returns `UnknownHandle` per existing port contract.

Tests deferred for the same reason as §A.

### Phase C wiring

`AsteraCombatPlugin.onEnable`, after the existing InMemory registrations:

```kotlin
registrations += services.register<IExplosionGateway>(
    PaperExplosionGateway(
        server, scheduler, eventBus, nearbyQuery, knockbackGateway,
        blockStateGateway = services.getOrThrow<IBlockStateGateway>(),
        damageApplier = ExplosionDamageApplier.None,
    ),
    priority = 100,
)
registrations += services.register<IAreaEffectGateway>(
    PaperAreaEffectGateway(
        server, scheduler, eventBus, nearbyQuery,
        damageApplier = services.getOrThrow<IDamageApplier>(),
        statusApplier = services.getOrThrow<IStatusEffectApplier>(),
        worldGateway = services.getOrThrow<IWorldGateway>(),
    ),
    priority = 100,
)
```

## Consequences

### Positive

- 2 RTM rows flip 🔌 → ✅ (combat AOE). Cumulative parity tick: +~1-2%.
- Phase 3 Siege gameplay primitives (TNT explosion destroying core blocks, AOE skills splashing) now actually visible in-world rather than ghost-firing through InMemory.
- Explosion routes through `IBlockStateGateway` → `IBlockChangeJournalRepository` (Wave 6c §B) → Postgres journal. Blocks destroyed by explosions survive `/restart` per the Wave 6c durability guarantee.
- Both adapters compose cleanly with the existing cross-plugin SPI chain (`IDamageApplier` / `IStatusEffectApplier` registered at p=100 by combat per ADR-0109; `IWorldGateway` at p=100 by core per Wave 6b-1).

### Negative / Trade-offs

- **`ExplosionDamageApplier.None` is wired by default** at Phase C. Explosion entity damage does NOT yet flow through the full modifier pipeline (armor reduction, crit, friendly-fire, combo, focus-fire). Wiring `ApplyDamageUseCase`-backed adapter is a 1-day Wave 6f follow-up that requires a small adapter class to fit `ExplosionDamageApplier` (per-target call) over `ApplyDamageUseCase` (returns Result). Wave 6e ships the adapter; downstream waves wire the real applier.
- **No Bukkit integration tests**. The Paper adapter contract is verified by-eye against the port + InMemory SSOT. Phase 1 PoC end-to-end smoke test will catch any divergence in-server.
- **`boundingRadius` over-approximates non-circle shapes**. A Box(10×10×4) explosion query treats the search as a sphere of half-diagonal ~7.5, applying actions to entities slightly outside the box's true bounds. The shape filter is implicit (caller scope-limits via spec). A future `IShapedNearbyQuery` port would fix this without changing the port surface.

### Mitigations

- The InMemory defaults remain registered at priority 0. If a Paper adapter regresses on a specific Bukkit build (e.g., a Paper 26.x API change to `createExplosion`'s parameter shape), operators can disable just the priority-100 registration without losing the port functionality.
- `ExplosionDamageApplier.None` is documented as the Wave 6e default in the adapter KDoc — any operator deploying Wave 6e gets explosions that visually fire but apply no entity damage. Phase 1 PoC ships with explosions that ONLY damage blocks (via the Astera block-damage path), which is honest given the modifier pipeline isn't wired yet.
- Konsist stays green at 58 rules. No new rule additions, no allow-set changes.

## Alternatives Considered

- **Wire `ExplosionDamageApplier` to `ApplyDamageUseCase` in this wave**: rejected. `ApplyDamageUseCase.execute(DamageAttempt)` returns `Result<DamageOutcome, ApplyDamageError>`; the SPI expects a per-target side-effect call with knockback-resistance pre-applied. A 30-line adapter would suffice but it's worth a separate review pass — Wave 6f follow-up.
- **Two-call workaround for `BlockDamagePolicy.Vanilla`** (`createExplosion(pow, false, false)` for visual + `createExplosion(0, false, true)` for blocks): rejected. Vanilla doesn't expose a "blocks-only no-entities" flag. The cancel-event approach via `BukkitEventAdapter` is simpler and already shipping.
- **Use `Display.interpolationDuration` for vanilla server-side tween** on AOE: rejected. AreaEffect shape evaluation is target-by-target per tick; vanilla interpolation operates on a single Display entity, doesn't help.
- **Implement `IShapedNearbyQuery` in this wave**: rejected. Refining the query surface is a port-level concern affecting more than just AreaEffect (Wave 6f hologram targeting, future ranged-weapon scope-aim). Defer to a dedicated port-introduction wave.

## Out of scope (deferred)

- **`ExplosionDamageApplier` real impl** (Wave 6f follow-up): adapter class wrapping `ApplyDamageUseCase`. Small.
- **`IShapedNearbyQuery` port** (future): fine-grained Box/Ring shape filtering.
- **`PaperHologramGateway` 16 per-type updaters** (Wave 6f-hologram-paper): the next-largest Paper impl.
- **Wave 6d-stub-warmup** (`PaperBlockStateGateway.warmUp(world)` driver call): independent of this wave.

## References

- [[adr/0088-physical-effects-aoe-foundation]] — Wave 5m source of the AOE port surface.
- [[adr/0149-astera-internal-hp-death-model]] — ADR-0149 §C invariant (no vanilla damage routing). The Paper Explosion adapter respects it via the cancel-vanilla-event path.
- [[adr/0152-paper-adapter-batch-food-movement-vision-camera-sign]] — `BukkitEventAdapter` (cancels vanilla `EntityDamageEvent`).
- [[adr/0156-paper-world-block-barrier-adapters]] — `IBlockStateGateway` Paper impl; consumed by Astera-mode block destruction.
- [[adr/0160-paper-production-hardening]] — Wave 6c §B `IBlockChangeJournalRepository` (durability persists across restart for explosion-broken blocks).
- [[adr/0161-paper-sweep-round-2]] — Wave 6d (preceding round); set the no-tests precedent for Bukkit-heavy adapters.
- [[adr/0109-effect-orchestration-foundation]] — `IDamageApplier` / `IStatusEffectApplier` cross-plugin SPI.
- `docs/RTM-PARITY-TRACKER.md` — 2 rows flip 🔌 → ✅.
