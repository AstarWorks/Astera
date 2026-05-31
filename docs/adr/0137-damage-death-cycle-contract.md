---
status: Accepted
date: 2026-05-31
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0137: Damage / death-cycle contract — IDamageGateway vs Bukkit PlayerDeathEvent

## Status

Accepted

## Context

Round 17 audit (3 parallel Explore agents pre-Wave-6 abstraction
verification) surfaced an unresolved design question in the damage chain
that will become a circular-dependency bug the moment Wave 6a
`PaperDamageGateway` is written without explicit guidance:

> Who fires `PlayerKilled` when HP reaches 0 — the Astera domain layer
> (via `ApplyDamageUseCase` reacting to `DamageOutcome.Killed`) or the
> Wave 6a `BukkitEventAdapter` (intercepting Bukkit's
> `PlayerDeathEvent` / `EntityDeathEvent`)?

The risk is concrete:

1. `ApplyDamageUseCase.execute()` (line 175-208) hands the resolved
   damage to `damageGateway.apply(target, resolved, source)`.
2. Wave 6a `PaperDamageGateway` will call
   `Damageable.damage(finalAmount)` to apply the hit via Bukkit's API.
3. If `finalAmount` brings the target's HP to 0, Bukkit's engine fires
   `PlayerDeathEvent` synchronously as part of the `damage()` call.
4. If `BukkitEventAdapter` (Wave 6a) listens to `PlayerDeathEvent` and
   re-routes through `ApplyDamageUseCase`, we get reentrancy:
   `apply()` → fires PlayerDeathEvent → adapter routes back into
   `ApplyDamageUseCase.execute()` → calls `apply()` on the same target
   that is now dead → `Damageable.damage()` on a dead entity is a no-op
   in Bukkit, but the second `ApplyDamageUseCase` invocation still
   publishes a second `PlayerKilled` event.

Without a contract, three failure modes are possible:

- **Double `PlayerKilled` publish**: the assist attribution
  (`AssistAttributionUseCase` line 23) reacts to `PlayerKilled` to
  publish `AssistCredited` — receiving it twice doubles every assist
  payout and pollutes stats.
- **Missing `PlayerKilled` for non-combat deaths**: void / fall / suicide
  / `/kill` go through `IPlayerHealthGateway.damage()` (admin path,
  separate from combat). If Wave 6a wires `BukkitEventAdapter` to
  re-route ALL `PlayerDeathEvent` through `ApplyDamageUseCase`, the
  vanilla suicide path tries to fabricate a `DamageAttempt` with no
  attacker → falls through error paths and the death is lost.
- **Bukkit re-fires after Astera already handled it**: vanilla mob
  drops + advancement progression depend on `EntityDeathEvent` firing
  exactly once per death. Re-routing through Astera's chain and back
  could double-fire if not careful.

The existing `ApplyDamageUseCase.execute()` already publishes
`PlayerKilled` on `DamageOutcome.Killed` (line 196-205) — this is the
authoritative Astera combat-death publish site. The Wave 6a author needs
to know: do NOT re-publish from the adapter; DO suppress vanilla's death
machinery when the death came from Astera.

## Decision

Establish a 2-path damage / death-cycle contract that Wave 6a
`BukkitEventAdapter` and `PaperDamageGateway` MUST follow:

### Path 1 — Astera combat death (the primary path)

```
attacker swings / casts
  → BukkitEventAdapter intercepts PlayerInteractEvent /
    EntityDamageByEntityEvent
  → cancels the Bukkit event (vanilla damage suppressed)
  → builds DamageAttempt
  → ApplyDamageUseCase.execute(attempt)
      ├─ DamageRule.resolve() → ResolvedDamage
      ├─ bonusSources fold → boostedResolved
      ├─ damageGateway.apply(target, boostedResolved, source)
      │     PaperDamageGateway:
      │       1. checks Player.isDead → return TargetAlreadyDead
      │       2. checks Player.noDamageTicks > 0 → return Immune
      │       3. Player.damage(boostedResolved.finalAmount) — Bukkit fires
      │          EntityDamageEvent (NOT cancelled by adapter; vanilla
      │          handles particle + sound from this) and may fire
      │          PlayerDeathEvent if HP→0
      │       4. reads Player.health post-damage
      │       5. returns HpReduced(remainingHp) or Killed
      ├─ on Killed: ApplyDamageUseCase publishes PlayerKilled
      └─ Result returned to adapter
```

**Critical**: `BukkitEventAdapter` MUST NOT additionally subscribe to
`PlayerDeathEvent` for the combat path. The vanilla event WILL fire from
inside `Player.damage()`, but Astera already published `PlayerKilled`
from `ApplyDamageUseCase` — re-publishing on the vanilla event would
double-fire.

To suppress the vanilla event side-effects (death message, sound, mob
loot drops the operator may want to control), `BukkitEventAdapter` can
optionally subscribe to `PlayerDeathEvent` and `event.deathMessage = null`
+ `event.drops.clear()` — but MUST NOT publish any Astera domain event
from that handler.

### Path 2 — Vanilla / non-combat death (suicide, void, fall, /kill)

```
player.health = 0 from vanilla source
  → Bukkit fires PlayerDeathEvent
  → BukkitEventAdapter intercepts PlayerDeathEvent
  → check: was this death caused by Astera combat?
  │   (track via a per-tick "recently-applied-by-Astera" set in the
  │    PaperDamageGateway impl, keyed by PlayerId, cleared next tick;
  │    if target is in the set, the death came from Path 1 and we
  │    skip re-handling)
  ├─ YES (combat) → suppress vanilla side-effects only, no domain publish
  └─ NO (vanilla)
       → synthesize DamageSource.Environment.<cause> via
         EntityDamageEvent.DamageCause inspection
       → publish PlayerHealthChanged via IPlayerHealthGateway
         (HP transition to 0)
       → publish PlayerKilled with killer = null, source = synthesized
       → handle vanilla death message + drops per game-mode policy
```

### PaperDamageGateway impl contract (Wave 6a)

```kotlin
class PaperDamageGateway(
    private val server: Server,
    private val tickClock: () -> Long,
) : IDamageGateway {
    // Cleared each tick by a 1-tick-period scheduler task.
    private val appliedThisTick: MutableSet<PlayerId> = ConcurrentHashMap.newKeySet()

    override fun apply(
        target: PlayerId,
        resolved: ResolvedDamage,
        source: DamageSource,
    ): Result<DamageOutcome, DamageApplicationError> {
        val player = server.getPlayer(target.uuid) ?: return Result.failure(TargetOffline(target))
        if (player.isDead) return Result.failure(TargetAlreadyDead(target))
        if (player.noDamageTicks > 0) return Result.failure(Immune(target))

        appliedThisTick.add(target)  // signal Path 2 adapter to skip
        player.damage(resolved.finalAmount)

        val postHp = player.health
        return if (postHp <= 0.0) Result.success(DamageOutcome.Killed)
        else Result.success(DamageOutcome.HpReduced(postHp))
    }

    fun clearAppliedTickMarker() {  // called by 1-tick scheduler task
        appliedThisTick.clear()
    }
}
```

### BukkitEventAdapter PlayerDeathEvent handler contract (Wave 6a)

```kotlin
@EventHandler(priority = EventPriority.MONITOR)
fun onPlayerDeath(event: PlayerDeathEvent) {
    val playerId = PlayerId(event.entity.uniqueId)
    if (paperDamageGateway.appliedThisTick.contains(playerId)) {
        // Path 1 — combat death from Astera; suppress vanilla side-effects
        // only. PlayerKilled already published by ApplyDamageUseCase.
        event.deathMessage = null  // operator may override per game-mode
        event.drops.clear()         // ditto
        return
    }
    // Path 2 — vanilla death (suicide / void / fall / /kill).
    val source = synthesizeDamageSource(event.entity.lastDamageCause)
    playerHealthGateway.damage(playerId, oldHp, source = HealthChangeSource.Other)
    eventBus.publish(PlayerKilled(victim = playerId, killer = null, source = source, atTick = tickClock()))
}
```

### Konsist Rule 56 — defer to Wave 6a

A text-scan rule "if `*.adapter.paper.*` contains class
`PaperDamageGateway`, its source MUST contain both `appliedThisTick` AND
`isDead` tokens" would mechanically enforce the contract. Land
**alongside** the Wave 6a adapter (rule born already-green); attempting
to land the rule today would have nothing to assert against.

## Consequences

### Positive

- Wave 6a author has unambiguous contract for both `PaperDamageGateway`
  and `BukkitEventAdapter.onPlayerDeath` handlers — including code
  sketches.
- No double-fire of `PlayerKilled` (assist attribution stays sane).
- Vanilla / non-combat deaths flow through a parallel,
  semantically-distinct path that the `appliedThisTick` marker cleanly
  separates from the combat path.
- `ApplyDamageUseCase.execute()` remains the single source of truth for
  combat `PlayerKilled` publish — no domain change needed.
- The `appliedThisTick` marker is a simple per-tick mutable set, cleared
  by a 1-tick scheduler task — minimal state, easy to reason about,
  bounded memory (max one entry per concurrent player taking damage in
  a single server tick).

### Negative / Trade-offs

- The `appliedThisTick` marker introduces a small piece of mutable state
  in `PaperDamageGateway` that must be cleared exactly once per tick.
  If the scheduler task is dropped (`onDisable` race / reload), the set
  could grow unbounded — mitigated by reading from a `ConcurrentHashMap`-
  backed set and bounding via Konsist Rule 56 or runtime assertion.
- Vanilla / non-combat deaths require synthesizing a `DamageSource` from
  Bukkit's `EntityDamageEvent.DamageCause` — a translation table that
  may grow over time as new vanilla damage causes are added. Acceptable
  per the existing `DamageSource.Environment` sealed hierarchy (already
  has Fall / Drown / Fire / Lightning / Void / Suffocation / Generic
  variants).
- The `appliedThisTick` set leaks PlayerId across ticks if the scheduler
  task is somehow late by a tick or more (e.g., main thread stall).
  Mitigation: scheduler task runs at tick start; even if late by N
  ticks, the set is cleared on the next firing — and the contract
  doesn't fail-open (a leaked entry just suppresses the vanilla
  side-effect handling for a stale entry, which has no user-visible
  effect since the player is already alive again or has logged out).

### Mitigations

- Wave 6a includes a unit test for `PaperDamageGateway` that verifies:
  (a) `appliedThisTick` is populated by `apply()` and (b) the
  1-tick scheduler task clears it.
- Wave 6a includes an integration test (or scenario test) that verifies:
  (a) combat death → one `PlayerKilled` publish, (b) vanilla death →
  one `PlayerKilled` publish from the adapter (not from
  `ApplyDamageUseCase`), (c) combat death does NOT trigger Path 2.
- Konsist Rule 56 (text-scan for `appliedThisTick` + `isDead`) lands
  alongside Wave 6a, refusing future contributor changes that remove
  the markers.

## Alternatives Considered

- **Alt A: Cancel `PlayerDeathEvent` in `BukkitEventAdapter` instead of
  using `appliedThisTick`**: rejected. `PlayerDeathEvent` is not
  cancellable in Bukkit (the entity is already dead at event-fire time).
- **Alt B: `PaperDamageGateway` directly publishes `PlayerKilled` and
  `ApplyDamageUseCase` skips the publish**: rejected. Inverts the
  responsibility split — Astera's domain layer should own domain event
  publishing; adapters should only translate I/O. Also breaks the
  symmetric pattern with `DamageDealt` / `KnockbackApplied` (both
  published by `ApplyDamageUseCase`, not the gateway).
- **Alt C: `PaperDamageGateway` returns `HpReduced(remainingHp = 0.0)`
  instead of `Killed` when target dies, letting downstream code decide
  death semantics**: rejected. `DamageOutcome` is explicitly designed
  with a `Killed` variant for this reason (per
  `DamageOutcome.kt` KDoc: "Callers inspect the variant to decide
  whether to publish `PlayerKilled`"). Conflating death with 0 HP
  reduction loses the semantic.
- **Alt D: Use a per-tick monotonic counter instead of a Set marker**:
  rejected. The Set is O(1) add/contains and cleared in O(N) once per
  tick (N typically <10). A counter requires per-player state on
  `Player`, not in the gateway.
- **Alt E: Defer the entire decision to Wave 6a author**: rejected
  (this ADR's whole point). Without the contract written down, the
  Wave 6a author faces a 50/50 design call where one choice silently
  breaks assist attribution and the other silently drops vanilla
  deaths.

## References

- 関連 ADR:
  [[adr/0022-multi-plugin-layout]],
  [[adr/0030-damage-pipeline-and-result-error]] (DamageOutcome variants:
  HpReduced / Killed — this ADR uses the existing semantic),
  [[adr/0080-player-physiology-resource-pool]] (Wave 5k — established
  `IPlayerHealthGateway.damage()` as the admin / non-combat HP-change
  path used by Path 2),
  [[adr/0128-cross-feature-integration-wire]] (Wave K — established
  `AssistAttributionUseCase` subscribing to `PlayerKilled`; this ADR
  protects assist attribution from double-fire),
  [[adr/0133-pre-paper-port-fill]] (Wave Q — InMemoryDamageGateway
  shipped at priority 0, returns `TargetOffline` for all; Wave 6a
  PaperDamageGateway overlays at priority 100 with the contract here)
- Related files:
  `plugin/combat/src/main/kotlin/.../application/usecase/damage/ApplyDamageUseCase.kt`
  (line 175-208 — authoritative `PlayerKilled` publish site for Path 1),
  `plugin/combat/src/main/kotlin/.../application/usecase/damage/AssistAttributionUseCase.kt`
  (line 23 — `PlayerKilled` subscriber that must not double-fire),
  `plugin/combat/src/main/kotlin/.../application/port/outbound/IDamageGateway.kt`
  (Wave 6a PaperDamageGateway implements this contract),
  `plugin/combat/src/main/kotlin/.../application/port/outbound/DamageOutcome.kt`
  (the `Killed` variant whose return triggers Path 1 PlayerKilled),
  `plugin/combat/src/main/kotlin/.../domain/damage/DamageSource.kt`
  (`Environment` sealed hierarchy used by Path 2 vanilla-death synthesis)
- Future work:
  - **Wave 6a**: `PaperDamageGateway` impl with `appliedThisTick`
    marker + 1-tick scheduler task; `BukkitEventAdapter.onPlayerDeath`
    handler with Path 1 / Path 2 branch. Konsist Rule 56 (text-scan for
    `appliedThisTick` + `isDead` tokens in `PaperDamageGateway` source)
    lands in the same commit.
  - **Wave 6a integration tests**: 3-case suite verifying single-fire
    PlayerKilled for combat death, single-fire for vanilla death, no
    cross-fire.
