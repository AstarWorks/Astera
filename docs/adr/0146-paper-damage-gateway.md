---
status: Superseded
date: 2026-05-31
deciders: Astera core
supersedes: null
superseded_by: 0149
---

# ADR-0146: PaperDamageGateway — Wave 6a-3

## Status

**Superseded by [[adr/0149-astera-internal-hp-death-model]]** (2026-06-02).
Body below preserved per Astera ADR convention (body immutable post-merge);
do not act on the design described here. See ADR-0149 §E for the
replacement `PaperDamageGateway` design (Astera-internal HP, no
`player.damage()` call, no vanilla math).

Originally: Accepted

## Context

Wave 6a-3 (Round 19). Third Paper sweep sub-wave. Closes ADR-0137
§Path 1 (Astera combat death) contract and lands Konsist Rule 57 (the
ADR-0137 deferred guard).

Per ADR-0137 the damage / death-cycle has two paths:

- **Path 1** (this wave): `ApplyDamageUseCase.execute` → `PaperDamageGateway.apply`
  → `Damageable.damage(finalAmount)` → post-HP read → return
  `HpReduced` / `Killed`. `ApplyDamageUseCase` then publishes
  `PlayerKilled` on `DamageOutcome.Killed` (existing line 196-205).
- **Path 2** (Wave 6a-6 — NOT this wave): vanilla / non-combat deaths
  (suicide / void / fall / `/kill`) routed by future
  `BukkitEventAdapter.onPlayerDeath` handler that consults
  `appliedThisTick` to distinguish Path 1 (skip) from Path 2
  (synthesize `DamageSource.Environment.<cause>` + publish
  `PlayerKilled(killer = null)`).

This wave ships **ONLY Path 1**. The `appliedThisTick` marker is wired
so Wave 6a-6 can ship Path 2 without changing this gateway. Vanilla
deaths in Phase 1 PoC remain un-tracked by Astera (Bukkit default
death message + drops fire); operators using `/heal` / `/kill` still
get the documented behaviour. Pulling Path 2 forward into this wave
would require the full BukkitEventAdapter event-translation surface
(15+ Bukkit event listeners) which belongs in Wave 6a-6.

## Decision

### §A — `PaperDamageGateway` impl

`plugin/combat/src/main/kotlin/com/astarworks/astera/combat/adapter/paper/PaperDamageGateway.kt`:

Constructor: `Server` + `IScheduler`.

`apply(target, resolved, source)` flow (matches ADR-0137 §Path 1 code
sketch):

1. `getPlayer(target.uuid)` → null = `TargetOffline`.
2. `player.isDead` → `TargetAlreadyDead`.
3. `player.noDamageTicks > 0` → `Immune` (Bukkit invulnerability frames
   after a recent hit; matches `EntityDamageEvent` vanilla i-frame
   semantics).
4. `appliedThisTick.add(target)` — **BEFORE** the `Damageable.damage`
   call so Bukkit's synchronous `PlayerDeathEvent` firing inside
   `damage()` sees the marker.
5. `player.damage(resolved.finalAmount)` — Bukkit fires
   `EntityDamageEvent` (vanilla particles + sound, vanilla i-frame
   tick reset) and, if HP→0, `PlayerDeathEvent` synchronously.
6. Read post-damage `player.health` → return `Killed` (≤ 0.0) or
   `HpReduced(remainingHp)`.

`ApplyDamageUseCase` publishes `PlayerKilled` on `DamageOutcome.Killed`
unchanged.

### §B — `appliedThisTick` marker lifecycle

- Field: `public val appliedThisTick: MutableSet<PlayerId> = ConcurrentHashMap.newKeySet()`.
- `public` so Wave 6a-6 `BukkitEventAdapter` can read it.
- `ConcurrentHashMap.newKeySet()` chosen as defensive concurrency for
  Wave 6a-6 reads — the per-tick clear task runs on main, but Wave 6a-6
  event handlers may also run on main, and Bukkit's
  `PlayerDeathEvent` handler thread guarantees are documented but
  brittle.
- Cleared by a 1-tick `IScheduler.runRepeatingTicks(1L, 1L)` task
  started via `PaperDamageGateway.start()`. Caller (AsteraCombatPlugin)
  invokes `start()` right after construction; the returned
  `IScheduler.TaskHandle` is held by `IScheduler` lifecycle (auto-cancels
  on Core disable / plugin unload) since Combat's `subscriptions` list
  is `IDomainEventBus.Subscription`-typed and incompatible.

### §C — Konsist Rule 57 (ADR-0137 deferred guard finally landing)

Text-scan `PaperDamageGateway` source under `*.adapter.paper.*` MUST
contain BOTH:

- `appliedThisTick` — removing it breaks Wave 6a-6 Path 1 vs Path 2
  detection → PlayerKilled double-fire → AssistAttributionUseCase
  double-pay.
- `isDead` — removing the dead-target short-circuit lets
  `Damageable.damage` fire on a corpse (Bukkit no-ops) while the gateway
  still returns `HpReduced(0.0)` — wrong semantic per ADR-0137 §Alt C.

Two separate `assertThat(file.text).contains(...)` checks within the
same `@Test` method, each with its own targeted fail message citing
ADR-0137 §rationale. Born already-green; pre-Wave-6a-3 file-absence =
trivially pass.

### §D — Registration at priority 100

`AsteraCombatPlugin.onEnable` between the existing InMemoryDamageGateway
registration (priority 0) and the existing `services.get<IDamageGateway>()`
lookup. The combat-bonus chain (Wave K) now runs through `Player.damage`
end-to-end in Phase 1 Paper deployments.

## Consequences

### Positive

- Wave 6a-3 ships — combat damage finally writes through to vanilla
  Bukkit `Damageable.damage`. Players actually take damage in PvP
  / PvE.
- Path 1 PlayerKilled publish remains the responsibility of
  `ApplyDamageUseCase` (unchanged); the gateway just signals success +
  outcome.
- `appliedThisTick` marker is the Path 1 ↔ Path 2 coordination point;
  Wave 6a-6 BukkitEventAdapter consumes it directly with no gateway
  change.
- Konsist Rule 57 prevents future regressions of either critical token
  (marker or isDead short-circuit).
- Bukkit invulnerability frames (`noDamageTicks`) honored as `Immune`,
  matching vanilla i-frame semantics — same player can't be machine-
  gunned by simultaneous Astera hits + vanilla i-frame skip.

### Negative / Trade-offs

- **Vanilla deaths un-tracked in this wave** — `/kill`, void, fall,
  suicide produce vanilla death message + drops but no Astera
  `PlayerKilled` publish. Wave 6a-6 closes via Path 2. Phase 1 PoC
  acceptable (vanilla path produces visible feedback; statistical
  attribution is the only loss).
- **`appliedThisTick` is `public`** — Wave 6a-6 needs cross-class read
  access. Encapsulation cost is documented in KDoc + Konsist Rule 57
  guards the invariant.
- **TaskHandle from start() is not held** — relies on IScheduler
  lifecycle to cancel on disable. Combat plugin's `subscriptions` list
  is `IDomainEventBus.Subscription`-typed; introducing a separate
  `MutableList<IScheduler.TaskHandle>` for this single handle was
  rejected as scope creep. Phase 1 PoC has no reload-during-combat
  scenario; if it becomes one, add the list in a follow-up.

### Mitigations

- Konsist Rule 57 protects both markers.
- KDoc on `appliedThisTick` explicitly documents the Wave 6a-6 read
  contract.
- `ConcurrentHashMap.newKeySet()` keeps the marker thread-safe
  defensively even though current operations are main-thread.

## Alternatives Considered

- **Alt A: Path 2 (vanilla deaths) in this wave**: rejected.
  `BukkitEventAdapter.onPlayerDeath` handler requires
  `EntityDamageEvent.DamageCause` → `DamageSource.Environment.<cause>`
  synthesis table + `IPlayerHealthGateway.damage` invocation + the
  full `BukkitEventAdapter` boilerplate (event listener registration,
  threading guards). Belongs in Wave 6a-6 alongside the rest of the
  Bukkit→DomainEvent translation surface.
- **Alt B: track TaskHandle in Combat's subscription list**: rejected
  for type-mismatch (Subscription vs TaskHandle). A separate list
  (`MutableList<IScheduler.TaskHandle>`) is the proper future fix when
  Combat ships more scheduler-backed adapters; currently one is the
  threshold for "no list yet".
- **Alt C: skip the noDamageTicks check**: rejected. Without it,
  combat code can stack multiple Astera hits on the same player in the
  same tick (bypassing vanilla i-frames). The `Immune` failure mode is
  the documented contract; Bukkit i-frames are the source of truth.
- **Alt D: defer Konsist Rule 57 to Wave 6a-6**: rejected. Per ADR-0137
  §Konsist Rule 56 "lands alongside actual adapter ... rule is born
  already-green" — landing now prevents the impl from drifting away
  from the contract before Wave 6a-6 ships its consumer.

## References

- 関連 ADR:
  [[adr/0022-multi-plugin-layout]],
  [[adr/0030-damage-pipeline-and-result-error]] (Wave 5a — DamageOutcome
  HpReduced/Killed variants this gateway returns),
  [[adr/0128-cross-feature-integration-wire]] (Wave K —
  AssistAttributionUseCase subscribes PlayerKilled; this ADR protects
  the subscriber from double-fire via §B marker contract),
  [[adr/0133-pre-paper-port-fill]] (Wave Q — InMemoryDamageGateway at
  priority 0; this Paper adapter overlays at priority 100),
  [[adr/0137-damage-death-cycle-contract]] (Wave T — established the
  2-path contract + appliedThisTick marker design this ADR
  implements),
  [[adr/0144-paper-knockback-gateway]] / [[adr/0145-paper-player-health-gateway]]
  (Wave 6a-1 + 6a-2 — sibling Paper adapter shape)
- Related files:
  `plugin/combat/src/main/kotlin/.../adapter/paper/PaperDamageGateway.kt`
  (new ~115 LOC),
  `plugin/combat/src/main/kotlin/.../platform/AsteraCombatPlugin.kt`
  (registration at priority 100 + start() call),
  `tools/architecture-test/src/test/kotlin/.../ArchitectureTest.kt`
  (Rule 57),
  `plugin/combat/src/main/kotlin/.../application/usecase/damage/ApplyDamageUseCase.kt:175-208`
  (PlayerKilled publish on DamageOutcome.Killed — unchanged)
- Future work:
  - Wave 6a-6 `BukkitEventAdapter.onPlayerDeath`: reads
    `appliedThisTick`, dispatches Path 1 (skip-only) vs Path 2
    (synthesize DamageSource.Environment + publish PlayerKilled).
  - Wave 6a-6: also wires `EntityDamageEvent` for Bukkit-to-Astera
    damage translation if non-Astera entities (mobs) hit players —
    out of scope for this combat-pipeline-only gateway.
  - Phase 2+: optional `IPlayerHealthGateway` consult-first integration
    in `apply` for armor-based last-stand mechanics (Wave 7c
    Annihilation-vision feature).
