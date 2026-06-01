---
status: Superseded
date: 2026-05-31
deciders: Astera core
supersedes: null
superseded_by: 0151
---

# ADR-0145: PaperPlayerHealthGateway — Wave 6a-2

## Status

**Superseded by [[adr/0151-paper-adapter-rewrite-astera-owns-damage]]**
(2026-06-02). Body below preserved per Astera ADR convention (body
immutable post-merge); do not act on the design described here. See
ADR-0151 §A for the replacement `PaperPlayerHealthGateway` design (thin
wrapper over container-backed InMemoryPlayerHealthGateway + Bukkit UI
mirror with `MIRROR_MIN_HP = 1.0` floor).

Originally: Accepted

## Context

Wave 6a-2 (Round 19 second sub-wave). Following ADR-0144 PaperKnockbackGateway
(first Paper sweep adapter, ~80 LOC, knockback path), this sub-wave lands
the **HP path** — Bukkit `Player.health` + `Attribute.MAX_HEALTH` write-back
with the same regen-scheduler semantics as the existing InMemory impl.

Per ADR-0080 §2 (Wave 5k two-tier health architecture), `IPlayerHealthGateway`
is the **Bukkit runtime mutation layer** that writes back to vanilla
attributes so the client UI shows the right hearts. The InMemory impl shipped
Phase 1 with no Bukkit-side writes; Wave 6a-2 closes that gap.

The HP path is a clean second sub-wave because:

1. The InMemory impl is a known-good behavioural reference (5 mutation
   methods + 2 read methods + regen scheduling pattern).
2. Bukkit API surface is straightforward: `Player.health`,
   `AttributeInstance.MAX_HEALTH`, `IScheduler.runRepeatingTicks`.
3. No cross-cutting deps (independent of damage cycle ADR-0137, knockback
   ADR-0136, BukkitEventAdapter).
4. Event publishing shape (`PlayerHealthChanged` / `PlayerMaxHealthChanged`
   / `PlayerRegenRateChanged`) is identical to InMemory, so HUD / achievement
   / analytics subscribers are impl-agnostic.

## Decision

### §A — `PaperPlayerHealthGateway` impl

`plugin/core/src/main/kotlin/com/astarworks/astera/core/adapter/paper/physiology/PaperPlayerHealthGateway.kt`
(new dir for core Paper physiology adapters):

Constructor: `Server` + `IScheduler` + `IDomainEventBus`.

Implements all 7 `IPlayerHealthGateway` methods:

- **`setMaxHealth(maxHp)`** → `player.getAttribute(Attribute.MAX_HEALTH).baseValue = maxHp`;
  publishes `PlayerMaxHealthChanged`; re-clamps `player.health` if it now
  exceeds new max (Bukkit does not auto-clamp on `setBaseValue`).
- **`setHealth(hp, source)`** → `player.health = hp.coerceAtMost(effectiveMax)`;
  publishes `PlayerHealthChanged` only when value actually changes.
- **`heal(amount, source)`** → reads `player.health`, calls `setHealth`.
- **`damage(amount, source)`** → reads `player.health`, calls `setHealth`
  with `(current - amount).coerceAtLeast(0.0)`. NOT the combat path
  (`IDamageGateway`); admin/system tier per ADR-0080.
- **`setRegenRate(hpPerSecond, ttlTicks)`** → cancels prior regen slot;
  schedules `runRepeatingTicks(1L, 1L)` calling `heal(perTick, Regen)`
  with `perTick = hpPerSecond / 20.0`; schedules optional expiry
  `runLaterTicks(ttlTicks)` calling `clearRegen`; publishes
  `PlayerRegenRateChanged`. Identical scheduling shape to InMemory.
- **`clearRegen`** → cancels both handles + publishes
  `PlayerRegenRateChanged(0.0, 0, ...)`. Idempotent.
- **`getCurrentHealth`** → `player.health` if online, null otherwise
  (matches contract: null = "vanilla default applies").
- **`getMaxHealth`** → `attribute.value` if online, null otherwise.

Paper 1.21+ Attribute enum: `Attribute.MAX_HEALTH` (no `GENERIC_` prefix —
consistent with `PaperItemMaterializer:215-222` convention).

### §B — Offline-player semantics

Every mutation method checks `server.getPlayer(playerId.uuid)` and returns
`HealthMutationError.BackendUnavailable("player offline")` when null. Per
ADR-0080 §HealthMutationError KDoc. Read-side returns null (vanilla
default applies) per the same contract.

### §C — Threading

Bukkit `Player.health` / `Player.setHealth` / `AttributeInstance.setBaseValue`
MUST be called on the main thread. The regen scheduler task is
`IScheduler.runRepeatingTicks { heal(...) }` — Paper's `BukkitScheduler`-
backed `IScheduler` dispatches on main. Documented in KDoc as a
contract for off-main callers (must round-trip through
`scheduler.runLater(Duration.ZERO, ...)`).

### §D — Registration at priority 100

`AsteraCorePlugin.onEnable` inserts the Paper adapter immediately after
the existing InMemory registration:

```kotlin
val playerHealthGateway: IPlayerHealthGateway =
    InMemoryPlayerHealthGateway(scheduler, eventBus)
services.register<IPlayerHealthGateway>(playerHealthGateway, priority = 0)

services.register<IPlayerHealthGateway>(
    PaperPlayerHealthGateway(server, scheduler, eventBus),
    priority = 100,
)
```

InMemory at priority 0 stays for unit tests / non-Paper deployments.
`services.get<IPlayerHealthGateway>()` returns the Paper impl in live
Bukkit runtime.

### §E — No Konsist guard (deferred to general Paper-adapter sweep)

Unlike ADR-0144 PaperKnockbackGateway which lands Konsist Rule 56 alongside
(for the resistance-consultation contract), this gateway has no equivalent
silent-drop pattern that requires a token-scan invariant. The contract
surface is the `IPlayerHealthGateway` interface itself (compile-checked
by Kotlin); any future refactor that breaks the contract fails at the
implementation level before reaching CI's Konsist phase.

If future hardening is needed (e.g. "PaperPlayerHealthGateway MUST read
Player.health on the main thread"), the rule can land in a follow-up wave
when the failure mode is documented.

## Consequences

### Positive

- Wave 6a-2 ships — second concrete Paper adapter after PaperKnockbackGateway.
- HP path becomes Bukkit-visible: `/heal <player>` admin commands, void
  damage, regen potions now write through to the player's heart UI.
- Domain events fire identically to InMemory shape; HUD adapters
  (Wave 6b PaperHudGateway etc.) get the right `PlayerHealthChanged`
  payloads without an impl branch.
- Regen scheduling shape mirrors InMemory exactly, so the existing
  `InMemoryPlayerHealthGatewayTest` regen scenarios remain authoritative
  domain coverage.
- 7-method coverage, ~200 LOC; clean Kotlin port of InMemory with
  Bukkit-API substitutions.

### Negative / Trade-offs

- **No unit test for `PaperPlayerHealthGateway`** — same constraint as
  ADR-0144 (no MockBukkit dep). InMemory unit tests + Konsist rules +
  integration test (Wave 6+ when a Paper test harness lands) provide
  coverage.
- **No `IAttributeContainer` integration in this wave** — per ADR-0080 §2
  KDoc, "PaperPlayerHealthGateway is the single point where the two
  meet (it consults `IAttributeContainer` first and then writes
  Bukkit)". This wave writes Bukkit only; the consult-container-first
  path is deferred to a follow-up wave when the use case becomes
  concrete (e.g. armor-set max-health bonus needs to win over admin
  setMaxHealth).
- **Regen tick task captures `playerId`** by closure; if the player logs
  out mid-regen, the task continues to fire and `heal()` returns
  `BackendUnavailable` (logged DEBUG). The TTL `expiryHandle` eventually
  cancels. Bounded but slightly wasteful — Phase 2 follow-up could wire
  a `PlayerQuitServer` subscriber that calls `clearRegen` proactively.

### Mitigations

- Per-tick `heal` failures log at DEBUG (not WARN) since "offline mid-regen"
  is a recoverable normal case, not an anomaly.
- TTL expiry task guarantees regen slots clean up eventually.
- `Attribute.MAX_HEALTH` null-safety: `getAttribute(...)?.value`
  defensively handles the rare case where Bukkit returns null (offline
  cache, world unload mid-tick).

## Alternatives Considered

- **Alt A: track local state map** (like InMemory's `health[playerId]`):
  rejected. Paper adapter's source of truth is Bukkit `Player.health`;
  duplicating in a Map would diverge on every external mutation (server
  /heal command, plugin write, vanilla damage). Read-through is simpler
  + correct.
- **Alt B: defer regen scheduling to Wave 6+**: rejected. Without regen,
  consumables / heal-over-time abilities don't work. The `IScheduler.
  runRepeatingTicks` integration is the natural Paper pattern.
- **Alt C: consult `IAttributeContainer` first in this wave**: rejected.
  Adds construction-time dependency (`IPlayerAttributeContainerStore`)
  before the consumer is concrete. Defer to follow-up wave where the
  use case (armor max-health bonus integration) is the driver.

## References

- 関連 ADR:
  [[adr/0022-multi-plugin-layout]],
  [[adr/0058-universal-attribute-container]] (Wave 5e — IAttributeContainer
  integration deferred to follow-up wave per §Alt C),
  [[adr/0080-player-physiology-resource-pool]] (Wave 5k — established the
  IPlayerHealthGateway two-tier split this ADR's adapter writes back to),
  [[adr/0086-admin-tooling-slow-query-feedback]] (Wave 5t — sibling
  IServerHealthCheck whose KDoc this ADR follows for offline-player
  semantics),
  [[adr/0144-paper-knockback-gateway]] (Wave 6a-1 — first Paper sweep
  adapter; this ADR ships the second)
- Related files:
  `plugin/core/src/main/kotlin/.../adapter/paper/physiology/PaperPlayerHealthGateway.kt`
  (new — ~200 LOC),
  `plugin/core/src/main/kotlin/.../platform/AsteraCorePlugin.kt:1062-1064+`
  (registration at priority 100),
  `plugin/core/src/main/kotlin/.../application/service/InMemoryPlayerHealthGateway.kt`
  (behavioural reference / unit-test source-of-truth)
- Future work (subsequent Wave 6a sub-waves):
  - Wave 6a-3: `PaperDamageGateway` + Konsist Rule 57 (per ADR-0137
    appliedThisTick + isDead token guard; depends on Wave 6a-6
    BukkitEventAdapter for Path 2 PlayerDeathEvent hijack)
  - Wave 6a-4: `PaperPlayerSpeedGateway` + `PaperPlayerFoodGateway`
    (resource pool family)
  - Wave 6a-5: `PaperStatusEffectAdapter` (Bukkit PotionEffect + icon
    halo)
  - Wave 6a-6: `BukkitEventAdapter` (15 ADR-0134 allowlist events +
    death-event hijack + PlayerInteractEvent skill cast)
  - Wave 6a-7+: `PaperPlayerMovementGateway` / `PaperPlayerVisionGateway`
    / `PaperCameraGateway` / `PaperSignActionService` etc. — trim
    ADR-0072 deferred allow-list
- Out of scope (Phase 2+):
  - `IAttributeContainer` consult-first integration in
    `getMaxHealth` / `setMaxHealth` (requires Wave 5e use case driver)
  - `PlayerQuitServer` subscriber that proactively clears regen on
    logout (Phase 2 cleanup polish)
