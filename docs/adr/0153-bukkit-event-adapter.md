---
status: Accepted
date: 2026-06-02
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0153: BukkitEventAdapter — Bukkit→DomainEvent translation + ADR-0149 §D defensive cancellation

## Status

Accepted. Ships the `BukkitEventAdapter` from Wave 6a-6, closing
**10 of the original 14 Wave-6a entries** in the ADR-0134 deferred-events
allowlist and **completing ADR-0149 §D** (defensive cancellation of
vanilla `EntityDamageEvent` + `PlayerDeathEvent`). Companion ADR
[[adr/0152-paper-adapter-batch-food-movement-vision-camera-sign]] ships
in the same commit.

## Context

ADR-0149 (Wave 6a-redesign Step 1) declared the Astera-internal HP /
death model and explicitly deferred §D — defensive cancellation of
vanilla damage / death paths — to "Wave 6a-6 when the BukkitEventAdapter
exists." ADR-0151 (Step 3) shipped `PaperDamageGateway` and
`PaperPlayerHealthGateway` with the `MIRROR_MIN_HP = 1.0` floor that
should prevent vanilla `PlayerDeathEvent` from firing under Astera
control, but vanilla intrusion paths (lava / fire / fall / void / drown
/ etc.) still bypass `PaperDamageGateway` because they originate inside
the Minecraft engine and never reach `ApplyDamageUseCase`.

ADR-0134's deferred-events allowlist also accumulated 14 Wave-6a
entries — DomainEvents whose publisher was waiting on a Bukkit-side
translation layer. Five DomainEvents are sibling-plugin types
(`match.*`, `combat.*`) that Core cannot publish per ADR-0022
sibling-isolation; the remaining 10 are Core-domain events that this
single adapter publishes.

A `BukkitEventAdapter` already existed at this path (30 LOC, sink-based
`(IMcEvent) -> Unit` for item click translation, single caller in
`AsteraItemsPlugin`). Per Phase 1 forward-only posture
(`CLAUDE.md §Pre-release posture`), the existing class is renamed to
`PaperClickEventBridge` (preserving its single-caller surface verbatim)
and the new wider `BukkitEventAdapter` takes the name.

## Decision

### §A — Role 1: Bukkit event → DomainEvent translation

`plugin/core/src/main/kotlin/.../adapter/paper/event/BukkitEventAdapter.kt`,
585 LOC, 12 `@EventHandler` annotations.

Constructor:
`(eventBus: IDomainEventBus, playerHealthGateway: IPlayerHealthGateway, scheduler: IScheduler)`.

Lifecycle: `start(plugin: JavaPlugin): IScheduler.TaskHandle` registers
the Listener via `plugin.server.pluginManager.registerEvents(this, plugin)`
and schedules the 20-tick time-polling task. The returned handle is held
by `AsteraCorePlugin` for cancellation on `onDisable`. Bukkit reclaims
the Listener via `HandlerList.unregisterAll(plugin)` automatically.

**10 Core-domain events translated** (closes the matching ADR-0134
allowlist entries):

| Bukkit event | Published Core DomainEvent |
|---|---|
| `PlayerJoinEvent` | `PlayerJoinedServer` |
| `PlayerQuitEvent` | `PlayerQuitServer` |
| `PlayerInteractEvent` (RIGHT/LEFT_CLICK_BLOCK only) | `PlayerInteractedBlock` |
| `PlayerTeleportEvent` | `PlayerTeleported` |
| `ChunkLoadEvent` | `ChunkLoaded` |
| `ChunkUnloadEvent` | `ChunkUnloaded` |
| `WorldUnloadEvent` | `WorldUnloading` |
| `WeatherChangeEvent` | `WeatherChanged` |
| `EntityDeathEvent` (non-player only) | `MobKilled` |
| world.fullTime polling (20-tick) | `TimeChanged` |

`TimeChanged` is poll-driven rather than event-driven (Bukkit has no
"time tick" event). Publish triggers: per-world `delta ≥ 100` ticks
since last publish, OR wrap-around (`delta < 0`). Per-world baseline
seeded silently on first observation to avoid a synthetic `0 →
currentTime` publish at server start. World baselines cleared on
`onWorldUnload` to prevent stale data for a recreated world of the
same name.

**4 sibling-plugin events deferred** (remain in ADR-0134 allowlist
with sibling-adapter target annotated):

- `match.EntityEnteredRegion` / `match.EntityLeftRegion` — pending
  Wave 6a-N `PaperRegionTracker` (needs `IRegionRegistry`, not shipped).
- `match.EntityPickedUpItem` — pending Wave 6a-N
  `PaperItemPickupAdapter` (sibling-isolation forbids Core from
  importing match-domain types).
- `combat.EnvironmentDamageApplied` — pending Wave 6a-N
  `PaperEnvironmentDamagePublisher` (Role 2 below already cancels +
  re-routes vanilla `EntityDamageEvent` through `IPlayerHealthGateway`;
  only the combat-domain publish itself is deferred).

Per-event safety:
- Each handler wraps `eventBus.publish` in a `safePublish { … }` inline
  try/catch + WARN log. A faulty subscriber cannot propagate an
  exception back into the Bukkit dispatch loop (which would risk
  plugin disable or infinite recursion).
- `PlayerInteractEvent` filters to `RIGHT_CLICK_BLOCK` /
  `LEFT_CLICK_BLOCK` only (`_AIR` variants fire even when no block is
  involved and would produce a nonsense `PlayerInteractedBlock` with a
  null position).
- `MobKilled.mobTypeId` synthesises a `bukkit:` namespace
  `MobTypeId` from `EntityType.name` (SCREAMING_SNAKE_CASE → lower-
  kebab); regex failure ⇒ skip with DEBUG log instead of throwing.
- `EntityDeathEvent` filters out players — `PlayerKilled` is published
  by the combat plugin from `ApplyDamageUseCase` per Wave 5e ADR-0058
  family; `MobKilled` is for non-player entities only.

### §B — Role 2: Vanilla intrusion defensive cancellation (ADR-0149 §D)

```kotlin
@EventHandler(priority = EventPriority.LOW)
public fun onEntityDamage(event: EntityDamageEvent) {
    if (event.entity !is Player) return
    val playerId = PlayerId(entity.uniqueId)
    when (event.cause) {
        ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, PROJECTILE,
        MAGIC, ENTITY_EXPLOSION ->
            // Astera combat path — ApplyDamageUseCase already handled
            // this. Skip; the PaperDamageGateway resolved Bukkit math
            // already (Wave 6a-redesign Step 3 / ADR-0151).
            return
        else -> {
            // Vanilla intrusion (lava / fire / fall / void / drown /
            // suffocation / freeze / cactus / lightning / poison /
            // wither / dragon-breath / fallable / wall-collision /
            // cramming / KILL / WORLD_BORDER / SUICIDE / THORNS /
            // CAMPFIRE / DRYOUT / SONIC_BOOM / CUSTOM / …).
            event.isCancelled = true
            val source = HealthChangeSource.Other("vanilla-${event.cause.name.lowercase()}")
            playerHealthGateway.damage(playerId, event.finalDamage, source)
        }
    }
}

@EventHandler(priority = EventPriority.LOW)
public fun onPlayerDeath(event: PlayerDeathEvent) {
    // ADR-0149 §C's MIRROR_MIN_HP=1.0 floor should prevent this from
    // ever firing under Astera control. If it does, the Astera-side
    // state is by definition out of sync with Bukkit — log WARN +
    // defensively revive so we don't lose the player to a vanilla
    // respawn screen.
    log.warn(
        "Vanilla PlayerDeathEvent fired despite MIRROR_MIN_HP=1.0 floor " +
        "— investigate Astera-side HP-mirror bug. player=${event.entity.name}",
    )
    event.showDeathMessages = false
    event.drops.clear()
    event.keepInventory = true
    event.keepLevel = true
    playerHealthGateway.setHealth(
        PlayerId(event.entity.uniqueId),
        1.0,
        HealthChangeSource.Other("defensive-revive"),
    )
}
```

The cause-list partition is exhaustive over all Bukkit DamageCause
variants known at Paper 26 (32 variants enumerated including new
`SONIC_BOOM` / `CAMPFIRE` / `DRYOUT`). The `else` branch catches
forward-compatible additions and routes them through the vanilla-
intrusion path — the conservative default (cancel + re-route) is
preferable to silent passthrough if a future Bukkit version adds a
new combat-path cause that should be filtered.

### §C — `PaperClickEventBridge` rename

The pre-existing 30-LOC `BukkitEventAdapter` (item click sink for
`AsteraItemsPlugin`) was renamed to
`plugin/core/.../adapter/paper/event/PaperClickEventBridge.kt` (44 LOC,
behaviour-preserving). Constructor, single `onInteract` handler, both
`LEFT_CLICK_AIR` / `LEFT_CLICK_BLOCK` → `IMcEvent.PlayerLeftClick{Air,Block}`
translations are identical to the prior class. The single caller in
`AsteraItemsPlugin.kt` and the KDoc cross-reference in
`ItemSkillTriggerListener.kt` were updated in the same commit.

Per Phase 1 pre-release posture, this is a one-step rename — no
typealias / deprecated alias / fallback shim.

### §D — `AsteraCorePlugin.onEnable` wiring

```kotlin
// Wired last so it sees all priority-100 Paper adapters registered first
// — including the PaperPlayerHealthGateway that Role 2 routes vanilla
// damage through.
val highestPriorityPlayerHealthGateway: IPlayerHealthGateway =
    services.get<IPlayerHealthGateway>() ?: playerHealthGateway
val bukkitEventAdapter = BukkitEventAdapter(
    eventBus = eventBus,
    playerHealthGateway = highestPriorityPlayerHealthGateway,
    scheduler = scheduler,
)
bukkitEventAdapterTimeTaskHandle = bukkitEventAdapter.start(this)
```

`onDisable` cancels `bukkitEventAdapterTimeTaskHandle`. Listener
un-registration is automatic via `HandlerList.unregisterAll(plugin)`.

## Consequences

### Positive

- 10 ADR-0134 allowlist entries close. The allowlist drops from 14
  Wave-6a entries to 4 sibling-plugin entries (each annotated with the
  Wave 6a-N adapter target).
- ADR-0149 §D fulfills. The "vanilla intrusion paths still kill players
  bypassing the MIRROR_MIN_HP=1.0 floor" hole closes — every Bukkit
  damage source either routes through `ApplyDamageUseCase` (Astera
  combat path) or through `IPlayerHealthGateway.damage` (vanilla
  intrusion path). The `MIRROR_MIN_HP=1.0` floor in
  `PaperPlayerHealthGateway` then prevents `player.health ≤ 0` from
  ever occurring under Astera control.
- The defensive `onPlayerDeath` handler is belt-and-suspenders — it
  should never fire, but if an Astera-side HP-mirror bug ever leaks
  through, the player is revived rather than dropped onto the vanilla
  respawn screen with a Wave 5e attribute container that thinks they
  still have HP.
- Listener-shaped Paper-adapter pattern is now precedent (companion
  ADR-0152 §A's `PaperPlayerFoodGateway` is the other ship in this
  commit using the same shape).

### Neutral / costs

- 4 sibling-plugin events remain deferred. Each is annotated in the
  ADR-0134 allowlist with the specific Wave 6a-N sibling adapter that
  will close it. Sibling-isolation is the load-bearing reason (Core
  cannot import match.* or combat.*) and is not negotiable.
- The `PaperClickEventBridge` rename is mechanical but touches one
  caller in `AsteraItemsPlugin` + one KDoc in `ItemSkillTriggerListener`
  — both edits land in the same commit as the new
  `BukkitEventAdapter`.

### Negative

- The `else` branch in Role 2's `DamageCause` partition is "cancel + re-
  route by default" rather than "passthrough by default." A future
  Bukkit version that adds a new combat-path cause (e.g. a new fire-aspect
  variant) would route through the vanilla-intrusion path until this
  ADR is updated. The conservative default is deliberate — silently
  letting an unrecognised combat cause apply double damage (Astera +
  Bukkit) is worse than the false-positive re-routing.

## Verification

- `./gradlew :plugin:core:compileKotlin` + `:plugin:items:compileKotlin` green.
- `./gradlew :tools:architecture-test:test` green:
  - ADR-0134 allowlist test passes with the 10 closed entries removed
    and the 4 sibling-deferred entries annotated.
  - Konsist Rule 56 (`PaperKnockbackGateway` token check) unaffected.
  - Konsist Rule 57 (`PaperDamageGateway` forbidden tokens) unaffected
    — this rule scans only files named `PaperDamageGateway`, not
    `BukkitEventAdapter`.
- `./gradlew check` green.

## References

- [[adr/0022-multi-plugin-architecture]] — sibling-isolation
- [[adr/0134-deferred-domain-event-publishers]] — allowlist that this
  ADR draws down by 10 entries
- [[adr/0149-astera-internal-hp-death-model]] — §D that this ADR fulfills
- [[adr/0151-paper-adapter-rewrite-astera-owns-damage]] — Step 3
  precedent; `PaperDamageGateway` whose `appliedThisTick` marker is the
  Astera-vs-vanilla-path discriminator implicit in Role 2's cause filter
- [[adr/0152-paper-adapter-batch-food-movement-vision-camera-sign]] —
  companion ADR (Wave 6a-5 + 6a-7)
