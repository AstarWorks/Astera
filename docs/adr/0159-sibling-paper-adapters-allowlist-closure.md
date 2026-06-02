---
status: Accepted
date: 2026-06-03
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0159: Sibling Paper adapters — ADR-0072 + ADR-0134 allowlist closure

## Status

Accepted. Post-PoC clean-up wave (Wave 6a-N3 + IStructurePaste stub).
**Empties both Konsist allow-sets** in a single commit:
- ADR-0072 `allowedMissingPaperImpl`: `IStructurePaste` removed → empty
- ADR-0134 deferred-events `# Wave 6a` section: 4 sibling entries
  removed → empty

After this commit Wave 6 has no remaining deferred entries; the
ADR-0072 + ADR-0134 allow-list mechanisms exist as guard rails only,
ready to capture *new* deferrals if a future wave introduces them.

## Context

Wave 6a-completion (ADR-0152/0153) shipped 10 of 14 Wave-6a Bukkit→
DomainEvent translations via Core's `BukkitEventAdapter`, but four
**sibling-plugin** events stayed in the ADR-0134 allowlist because
ADR-0022 sibling-isolation forbids Core from publishing
`match.*` / `combat.*` domain events:

- `match.EntityEnteredRegion` / `match.EntityLeftRegion`
- `match.EntityPickedUpItem`
- `combat.EnvironmentDamageApplied`

Separately, ADR-0072's `allowedMissingPaperImpl` Konsist set retained
`IStructurePaste` — no Paper-side (FAWE) impl, but its in-memory
behaviour is fully captured by `FakeStructurePaste` in test-fixtures.
Phase 1 PoC scenarios that exercise anomaly-spawned-structure paste/
undo round trips would work fine with the same in-memory delta
capture if it lived in production code.

This wave closes both at once.

## Decision

### §A — `RegionTransitionRelayListener` (match)

`plugin/match/.../application/eventbus/listeners/region/RegionTransitionRelayListener.kt`,
96 LOC.

A pure event-bus relay (no Bukkit Listener — Core's
`RegionTransitionDetectorListener` already does the Bukkit-side work
and publishes `core.PlayerEnteredRegion` / `PlayerExitedRegion`).
This listener subscribes to those Core events, resolves
`playerId → matchId` via `IMatchRepository.findMatchOf`, and
republishes as `match.EntityEnteredRegion` / `match.EntityLeftRegion`
with the original `atTick` carried through (not resampled — accurate
to the moment of transition, not the moment of relay).

Constructor `(eventBus, matches)`. `subscribe(): List<IDomainEventBus.Subscription>`
returns the two subscriptions for the caller to hold; matches the
existing `StealJewelryListener` precedent (callers add to the shared
`subscriptions` list cancelled in `onDisable`).

Players not currently in a match (lobby / free-roam) silently no-op
— the relay produces a match-domain event only when there is a
match-domain context to attach.

### §B — `PaperItemPickupAdapter` (match)

`plugin/match/.../adapter/paper/PaperItemPickupAdapter.kt`, 158 LOC.

`org.bukkit.event.Listener` + `start(plugin)` registers
`@EventHandler(MONITOR, ignoreCancelled=true) onEntityPickupItem`.
Resolves `playerId → matchId` like the relay; non-match pickups
(lobby / spectator) silently no-op.

`itemId` synthesis:
1. Try Bukkit `PersistentDataContainer.get(NamespacedKey(plugin, "item_id"), STRING)`
   — produces the canonical Astera id (`astera:tnt_carry`) when the
   item was minted by `PaperItemMaterializer`.
2. Fallback `bukkit:<lower-kebab material-name>` (mirrors
   `BukkitEventAdapter.bukkitEntityTypeToMobTypeId` convention).

The `NamespacedKey(plugin, "item_id")` string `"item_id"` duplicates
`items.adapter.paper.PaperItemMaterializer.ITEM_ID_KEY` — ADR-0022
sibling-isolation forbids match from importing items. KDoc flags the
duplication and notes the promote-to-Core path if a third plugin
needs the same key.

Subscriber exception isolation via try/catch + WARN log; per-pickup
performance acceptable for Phase 1 PoC volume.

### §C — `PaperEnvironmentDamagePublisher` (combat)

`plugin/combat/.../adapter/paper/PaperEnvironmentDamagePublisher.kt`,
214 LOC.

`org.bukkit.event.Listener` + `start(plugin)` registers
`@EventHandler(LOWEST, ignoreCancelled=false) onEntityDamage`.

**Priority `LOWEST` runs before Core BukkitEventAdapter's `LOW`** so
the combat-domain event publishes first; Core then cancels +
re-routes via `IPlayerHealthGateway.damage` per ADR-0149 §D. Two
separate handlers fire on the same event with distinct roles —
publish-only here, cancel + re-route in Core.

Cause partition mirrors `BukkitEventAdapter` Role 2 exactly:
- **Skip** (Astera combat path owns analytics): `ENTITY_ATTACK`,
  `ENTITY_SWEEP_ATTACK`, `PROJECTILE`, `MAGIC`, `ENTITY_EXPLOSION`.
- **Publish** for everything else (vanilla intrusion + forward-
  compatible unclassified causes).

`EnvironmentDamageApplied` actual fields (verified against
`combat.domain.event.aoe.EnvironmentDamageApplied.kt`):
```kotlin
data class EnvironmentDamageApplied(
    val target: PlayerId,                       // NOT playerId
    val source: DamageSource.Environment,       // sealed (Wave 5m / ADR-0088)
    val finalDamage: Double,                    // NOT amount; >= 0.0 invariant
    val atTick: Long,
    ...
)
```

`source` is the sealed `DamageSource.Environment` hierarchy
(`Fall(distance)`, `Drown(remainingAir)`, `Fire(ticksOnFire)`,
`Lightning`, `Void`, `Suffocation`, `Generic`). The publisher pulls
`player.fallDistance` / `player.remainingAir` / `player.fireTicks`
to populate the rich variants and falls back to `Generic` for
everything else.

`event.finalDamage.coerceAtLeast(0.0)` defensive guard — some Paper
builds report negative `finalDamage` on fully-absorbed hits; the
data class's `require(finalDamage >= 0.0)` would throw otherwise.

### §D — `InMemoryStructurePaste` (core, Phase 1 stub)

`plugin/core/.../application/service/InMemoryStructurePaste.kt`,
~60 LOC.

Same shape as the existing test-fixture `FakeStructurePaste`
(promoted out of test-only namespace so it can register in
`AsteraCorePlugin.onEnable` at priority 0 without dragging test
fixtures into production classpath). `paste` mints a fresh
`PasteHandle(UUID.randomUUID())` and records `(schematic, world,
offset)`; `undo` drops the record (idempotent per port contract).

**No real world I/O.** Anomaly-spawned-structure use cases that
paste-then-undo work end-to-end at the domain level; visible block
mutation lands when Wave 6b+ FAWE-backed `PaperStructurePaste`
overlays at priority 100.

Naming convention divergence from ADR-0072 ("PaperStructurePaste"
reserved name): the contract is pure Kotlin until FAWE wires real
I/O — same posture as ADR-0154 `MatchTimerService`. The Konsist
allow-set entry is removed in the same commit; the empty allow-set
makes the next deferral introduction explicit.

### §E — Wiring (orchestrator Phase C)

`AsteraCorePlugin.onEnable` (after `ISchematicLoader` registration at
line ~584):
```kotlin
services.register<IStructurePaste>(InMemoryStructurePaste(), priority = 0)
```

`AsteraMatchPlugin.onEnable` (after `PaperBarrierGateway` registration
at line ~610):
```kotlin
subscriptions += RegionTransitionRelayListener(eventBus = eventBus,
    matches = matches).subscribe()
PaperItemPickupAdapter(
    matchRepository = matches,
    eventBus = eventBus,
    scheduler = scheduler,
).start(this)
```

`AsteraCombatPlugin.onEnable` (just before the final `logger.info("Astera-Combat
enabled. ...")` at line ~708):
```kotlin
PaperEnvironmentDamagePublisher(eventBus, scheduler).start(this)
```

No `onDisable` cleanup required for any of the three Listeners —
Bukkit's `HandlerList.unregisterAll(plugin)` handles teardown
automatically. `RegionTransitionRelayListener` subscriptions ride
the existing `subscriptions: MutableList<IDomainEventBus.Subscription>`
cleanup loop in `AsteraMatchPlugin.onDisable`.

## Consequences

### Positive

- **ADR-0072 + ADR-0134 allow-sets are empty.** Wave 6 has no
  remaining deferred entries. The Konsist guard rails stay in place
  for future deferrals (positive-shrink rule + publisher-must-exist
  rule both unchanged).
- ADR-0149 §C invariant preserved everywhere: Combat publisher is
  publish-only (no `event.isCancelled` mutation — that's Core's job);
  match adapters don't touch player HP; structure paste is in-memory
  no-op.
- The publish/cancel split for `EntityDamageEvent` (combat at LOWEST
  publishes; Core at LOW cancels + reroutes) is clean and survives
  forward-compatible Bukkit additions because both handlers use the
  same exhaustive cause partition.
- 4 new in-process subscribers can now react to vanilla-intrusion
  damage (shield consumption / fire-tick status / fall-damage
  analytics) and 3 to in-match region transitions (CarryTNT /
  CaptureWool / StealJewelry) without polling.

### Neutral / costs

- `PaperItemPickupAdapter` duplicates the PDC key string `"item_id"`
  with `PaperItemMaterializer` per ADR-0022 sibling-isolation
  (cannot import items from match). Acceptable Phase 1 PoC trade;
  promote to a Core constant when a third plugin needs the same key.
- `InMemoryStructurePaste` is a stub — wave-end smoke tests should
  not depend on real world geometry mutation until Wave 6b+ FAWE
  adapter ships.
- 5 files / ~728 new LOC across 3 plugins (3 adapters + 1 stub +
  Konsist allow-set housekeeping).

### Negative

- `PaperEnvironmentDamagePublisher` LOWEST + Core BukkitEventAdapter
  LOW means TWO Bukkit handlers fire on every `EntityDamageEvent`.
  Acceptable overhead (a `when` exhaustive on `DamageCause` + a
  conditional publish); both early-return for combat-path causes so
  the steady-state path is small. Worth re-evaluating if
  `EntityDamageEvent` becomes a profile hot-spot.

## Verification

- `./gradlew check` BUILD SUCCESSFUL.
- ADR-0072 `Wave 6 Paper impl allow-list` test: passes with empty
  `allowedMissingPaperImpl` set.
- ADR-0134 `every DomainEvent must have a publisher` test: passes
  with the 4 Wave-6a sibling entries removed from the allowlist
  (their publishers ship in this commit).
- 58 Konsist rules green.

Manual integration check (post-PoC scenarios):
1. Player walks into `red_base` region during a match → Core
   `RegionTransitionDetectorListener` publishes `PlayerEnteredRegion`
   → match `RegionTransitionRelayListener` publishes
   `EntityEnteredRegion(matchId, ...)` → mode listener
   (CarryTNT/CaptureWool/StealJewelry) reacts.
2. Player picks up a TNT item during a match → Bukkit
   `EntityPickupItemEvent` → `PaperItemPickupAdapter` publishes
   `EntityPickedUpItem(matchId, playerId, "astera:tnt_carry", 1,
   atTick)` → CarryTNT mode listener marks player as carrier.
3. Player walks into lava → Bukkit `EntityDamageEvent(cause=LAVA)` →
   `PaperEnvironmentDamagePublisher` (LOWEST) publishes
   `EnvironmentDamageApplied(target, DamageSource.Environment.Fire(
   ticksOnFire), finalDamage, atTick)` → Core BukkitEventAdapter
   (LOW) cancels event + calls `IPlayerHealthGateway.damage(...)` →
   Astera HP decrement.

## References

- [[adr/0022-multi-plugin-architecture]] — sibling-isolation
  (the reason all four entries needed sibling-plugin adapters)
- [[adr/0072-wave-6-paper-impl-allow-list]] — allow-set this wave
  empties
- [[adr/0088-aoe-and-environmental-damage]] — `DamageSource.Environment`
  sealed hierarchy `PaperEnvironmentDamagePublisher` populates
- [[adr/0134-deferred-domain-event-publishers]] — allowlist this
  wave shrinks by 4
- [[adr/0149-astera-internal-hp-death-model]] — §D vanilla-intrusion
  cancel + reroute invariant preserved
- [[adr/0152-paper-adapter-batch-food-movement-vision-camera-sign]] —
  Listener-shaped Paper adapter precedent (`PaperPlayerFoodGateway` §A)
- [[adr/0153-bukkit-event-adapter]] — Core sibling-publisher Role 2
  this wave complements
- [[adr/0154-match-timer-service]] — pure-Kotlin allow-set shrink
  precedent (no `adapter/paper/` requirement when no Bukkit deps)
