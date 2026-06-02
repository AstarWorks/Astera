---
status: Accepted
date: 2026-06-02
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0155: Combat polish Paper sweep — status effects, projectiles, cooldown publishing

## Status

Accepted. Ships Wave 6a-N2 — three independent Paper concretes / wiring
gaps in the combat plugin closed together as a single batch. Builds on
the Wave 6a-redesign (ADR-0149/0150/0151) Astera-owns-damage-math
invariant; no override of that posture.

## Context

After Wave 6a-completion-batch (ADR-0152 + ADR-0153) the Core-side
Paper sweep was essentially complete (Knockback / Health / Damage /
Speed / Food / Movement / Vision / Camera / Sign / BukkitEventAdapter
all shipped). Three combat-plugin gaps remained that the
`IMPLEMENTATION-ROADMAP.md` §"Wave 6a — Paper Sweep, Combat" row
listed but no prior wave delivered:

1. **`IStatusEffectContainer`** — port + `FakeStatusEffectContainer`
   shipped pre-Wave 6 but no production impl mirrored to Bukkit
   `PotionEffect`. Status effects existed in Astera memory only;
   vanilla potion icons / removal events were not wired.
2. **`IProjectileService`** — port shipped pre-Wave 6 but no impl
   spawned actual Bukkit projectile entities; skill casts that
   produced projectiles silently no-op'd via the inline
   `NoOpProjectileHandle` in `AsteraCombatPlugin`.
3. **`TickCooldownUseCase`** — instantiated but discarded
   (`@Suppress("UNUSED_VARIABLE")`); never scheduler-wired; the
   `CooldownExpired` domain event that the KDoc declared "intentionally
   deferred" was never created. HUD / sound subscribers had to poll
   `ICooldownTracker.findExpired` themselves.

These three gaps blocked the Phase 1 PoC smoke-test items #6 (左クリック
発射 → projectile trail → mob HP 減) and #7 (burn status 適用 →
potion icon → N秒後 expire event) per ROADMAP §"Phase 1 PoC
end-to-end 完成".

## Decision

### §A — `PaperStatusEffectContainer` (sub-A)

`plugin/combat/.../adapter/paper/PaperStatusEffectContainer.kt`, 277 LOC,
5 `IStatusEffectContainer` overrides. ADR-0149 §C precedent: Astera
internal map is source of truth; Bukkit `PotionEffect` is UI mirror
only.

Constructor: `(server, scheduler, eventBus, statusEffectRegistry)`.

Per-method shape:
1. Offline-player check (`server.getPlayer(uuid)` null ⇒ silent state
   update + DEBUG log; port returns `Unit` so no error variant).
2. Update Astera internal `MutableMap<PlayerId, MutableMap<StatusEffectId, ActiveEffect>>`.
3. Best-effort Bukkit mirror via
   `bukkitPotionTypeFor(astera StatusEffectKind)` — vanilla-equivalent
   kinds (POISON / WITHER / SLOWNESS / SPEED / REGENERATION / etc.)
   map to Bukkit `PotionEffectType`; custom Astera kinds skip the
   Bukkit write with DEBUG log (icon mirror lands in Wave 6b custom
   packet adapter).
4. Publish domain event (`StatusEffectApplied` / new `StatusRemoved` /
   new `StatusExpired`).

`StatusEffectTickListener` (companion file, 323 LOC) is the
scheduler-driven complement:
- `org.bukkit.event.Listener` + `start(plugin): IScheduler.TaskHandle`
  registers the listener via `pluginManager.registerEvents` and
  schedules `scheduler.runRepeatingTicks(1L, 1L) { tickAll() }`.
- `tickAll()`: per-player active effects walk; decrements remaining
  duration; expire ⇒ publish `StatusExpired` + Bukkit
  `removePotionEffect`. Tick-based kinds (POISON / REGENERATION)
  apply per-tick damage / heal via `IPlayerHealthGateway.damage` /
  `.heal`.
- `@EventHandler onEntityPotionEffectEvent(event: EntityPotionEffectEvent)`:
  vanilla potion removal (milk bucket consumed, mob-aura expiration)
  ⇒ remove from Astera internal map + publish `StatusRemoved`. Keeps
  Astera state consistent with the vanilla world.

Two new domain events land in `combat/domain/event/`:
- `StatusRemoved` — non-expiry removal (cleared by command, milk
  bucket, defensive aura drop).
- `StatusExpired` — natural duration-end expiry.

### §B — `PaperProjectileService` + `ProjectileHitListener` (sub-B)

`plugin/combat/.../adapter/paper/PaperProjectileService.kt`, 559 LOC.
`ProjectileHitListener.kt`, 299 LOC.

Constructor (service): `(server, scheduler)`. No `eventBus` because no
`ProjectileSpawned` / `ProjectileHit` domain events exist today — the
existing `DamageSource.Projectile` already carries projectile
attribution through the `DamageDealt` / `CriticalHit` / `KnockbackApplied`
event chain so analytics already see it.

5 motion variants (matching the actual `MotionProfile` sealed
hierarchy, not the brief's sketch):
- `Linear` — velocity assignment + optional per-tick drag decay.
- `Ballistic` — velocity + vanilla gravity + per-tick drag.
- `Homing` — per-tick nearest-`LivingEntity` acquisition within
  `acquisitionRadius`, blended course correction bounded by
  `turnRatePerTick`.
- `Curved` — per-tick parametric path along `targetOffset` driven by
  elapsed / horizon ratio; teleports + sets tangent velocity.
- `Beam` — high finite carrier velocity (POSITIVE_INFINITY clamped to
  100 blocks/tick); watchdog evicts on next tick.

ADR-0149 §C compliance: the Bukkit projectile entity is a **visual
carrier only**. Damage resolution flows through
`ProjectileHitListener → ApplyDamageUseCase → PaperDamageGateway` so
the existing `appliedThisTick` marker prevents double-counting; no
`bukkitEntity.damage(...)` calls anywhere. `shooter` is set only for
vanilla kill-credit attribution (cosmetic).

`ProjectileHitListener` (two `@EventHandler`s):
- `onProjectileHit(ProjectileHitEvent)` (priority LOW) — looks up
  Astera tracking by Bukkit projectile UUID, dispatches `spec.onHit`
  (`DealDamage` / `InflictStatus` wired Phase 1; `Explode` /
  `SpawnChild` / `PlaySound` / `SpawnParticle` log-at-debug and held
  for Phase 6 per `OnHitEffect` sealed hierarchy `IExplosionGateway` /
  `IParticleScheduler` / `ISoundRegistry` dependencies), then
  untracks.
- `onEntityDamageByEntity(EntityDamageByEntityEvent)` (priority LOW,
  ignoreCancelled=true) — Astera-tracked projectile ⇒ cancel +
  Astera-route. Non-Astera projectiles fall through to Core's
  `BukkitEventAdapter` Role 2 vanilla-intrusion handler (ADR-0153).

### §C — `TickCooldownUseCase` + `CooldownExpired` (sub-C)

`plugin/combat/.../domain/event/CooldownExpired.kt`, 37 LOC, new
domain event. Fields: `playerId`, `cooldownKey: CooldownKey` (sealed
preserved — SkillId / Weapon / Status / Custom variants carry the
full discriminator), `expiredAtTick`, `occurredAt`, `version = 1`.

`TickCooldownUseCase` gains `eventBus: IDomainEventBus` constructor
arg (3rd parameter, no default — Phase 1 forward-only break to a
2-call-site surface: `AsteraCombatPlugin` + `TickCooldownUseCaseTest`).
`execute()` now publishes `CooldownExpired(playerId, key, currentTick)`
per expired pair returned by `cooldownTracker.tick(currentTick)`.

`AsteraCombatPlugin.onEnable` wires the scheduler:

```kotlin
val tickCooldown = TickCooldownUseCase(cooldownTracker, scheduler, eventBus)
tickCooldownHandle = scheduler.runRepeatingTicks(1L, 1L) { tickCooldown.execute() }
```

Pre-existing `@Suppress("UNUSED_VARIABLE")` annotation removed; the
existing line 425-428 instantiation that never ran is now properly
driven.

`TickCooldownUseCaseTest` updated to inject `FakeDomainEventBus`,
verifies `publishedOfType<CooldownExpired>()` reflects each expired
pair, plus negative tests (no cooldowns / not yet expired ⇒ no events).

### §D — `AsteraCombatPlugin` wiring

Five wiring changes inside `onEnable`:

1. `PaperStatusEffectContainer` constructed + registered at priority
   100 **before** `services.get<IStatusEffectContainer>()` resolves
   (was the ordering caveat sub-A flagged — orchestrator confirmed by
   moving the resolution after the registration).
2. `StatusEffectTickListener` constructed + started immediately after
   the container is registered. Resolves `IPlayerHealthGateway` via
   `services.getOrThrow` so it picks up Core's priority-100 Paper
   impl (ADR-0151). Handle stored in `statusTickHandle: TaskHandle?`.
3. `PaperProjectileService` constructed + `start()` called +
   registered at priority 100; instance stored in
   `paperProjectileService: PaperProjectileService?` for
   `onDisable.stop()`. The legacy `NoOpProjectileHandle` fallback
   removed; `effectiveProjectileService` is now just the resolved
   port.
4. `ProjectileHitListener` constructed + `start(this)` inside the
   `if (damageGateway != null && statusContainer != null && cooldownTracker != null)`
   block since it needs `ApplyDamageUseCase` + `ApplyStatusUseCase`.
5. `TickCooldownUseCase` constructor gains `eventBus = eventBus`;
   `tickCooldownHandle = scheduler.runRepeatingTicks(1L, 1L) { tickCooldown.execute() }`.

`onDisable` cancels `statusTickHandle`, `tickCooldownHandle`, and
`paperProjectileService.stop()` before the existing
`subscriptions.cancel()` / `registrations.unregister()` loop. Bukkit
reclaims listener registrations automatically via
`HandlerList.unregisterAll(this)`.

### §E — Out-of-scope (Wave 6b / Phase 6)

- `OnHitEffect` Phase 6 variants (`Explode` / `SpawnChild` /
  `PlaySound` / `SpawnParticle`) log-at-debug instead of executing.
  Each Phase 6 holdup names the dependency it waits on
  (`IExplosionGateway` integration; parent-velocity inheritance for
  `SpawnChild`; `IParticleScheduler` / `ISoundRegistry` injection).
- True sub-tick instant raycast for `Beam` motion (zero-tick beam
  impact) — current `IProjectileService.spawn` port doesn't return
  enough metadata to synchronously dispatch `OnHitEffect`. Deferred
  to Wave 6b packet-adapter work.
- Status-effect visual fidelity beyond vanilla-equivalent
  `PotionEffectType` mapping (custom amplifier glow / custom particle
  trail) — needs Wave 6b custom packet adapter.

### §F — Pre-existing bug surfaced

`ProjectileSpec.onHit.isNotEmpty()` invariant: existing
`ProjectileHandler` (skill dispatch, line 73 prior to this wave)
passes `onHit = emptyList()` which violates `ProjectileSpec.init`.
**Not introduced here, just surfaced** by sub-B's walkthrough.
Recommend a follow-up wave to "synthesize a `DealDamage` from
`params.damage` when `phases.hitEntity` is empty"; not in this ADR's
scope to fix.

## Consequences

### Positive

- Three Phase 1 PoC smoke-test scenarios become testable end-to-end:
  burn status now shows a Bukkit `PotionEffectType.POISON` icon and
  expires via either Astera tick-driven timeout or vanilla potion
  removal; projectile skills spawn actual Bukkit entities with
  visible trail; cooldown expiry pushes `CooldownExpired` events that
  HUD / sound subscribers can subscribe to without polling.
- The Wave 6a Paper sweep declared in IMPLEMENTATION-ROADMAP §Wave 6a
  is now complete except for `IStructurePaste` (deferred to Wave 6b
  per ADR-0072) and 4 sibling-plugin ADR-0134 allowlist entries
  (deferred to post-PoC Wave 6a-N3).
- ADR-0149 §C invariant preserved everywhere: Bukkit projectile
  entity is a visual carrier (no `damage()` call); status mirror is
  vanilla potion only (Astera owns expiry math); cooldown tick reads
  Astera `ICooldownTracker` (no Bukkit cooldown coupling).

### Neutral / costs

- Two new domain events (`StatusExpired`, `StatusRemoved`) expand the
  cross-plugin event vocabulary by 2. `StatusRemoved` distinguishes
  non-expiry removal (milk bucket / command) from natural expiry —
  HUD listeners that want different visual feedback per cause now
  have the discriminator.
- `bukkitPotionTypeFor` mapping is duplicated across
  `PaperStatusEffectContainer` (private) + `StatusEffectTickListener`
  (private). Sub-A flagged this as an ADR-0155 follow-up: extract a
  `StatusEffectBukkitMirror` helper. Acceptable Phase 1 PoC cost
  (24 LOC duplication).
- `TickCooldownUseCase` constructor is now 3-arg with no default.
  Two call sites updated in this wave; no production drift.

### Negative

- `OnHitEffect` Phase 6 holds means projectile skills that declare
  `Explode` / `SpawnChild` / `PlaySound` / `SpawnParticle` on-hit
  effects will compile + spawn but visually no-op until Wave 6b
  packet adapter ships. The DEBUG log per skip is the only signal.

## Verification

- `./gradlew :plugin:combat:compileKotlin` green.
- `./gradlew :tools:architecture-test:test` green (no new Konsist
  rules required; the existing `combat domain must not import bukkit
  projectile api` rule unaffected — both new Paper files live in
  `combat.adapter.paper.*` which already permits Bukkit imports).
- `./gradlew check` BUILD SUCCESSFUL.

Manual integration check (Wave 6b smoke test prerequisites):
1. Cast a burn skill ⇒ player gets `PotionEffectType.POISON` icon;
   after duration, `StatusExpired` fires + icon disappears.
2. Cast a projectile skill ⇒ Bukkit `Arrow` / `Snowball` / etc.
   visibly spawns + follows motion profile; on hit, mob HP drops
   (Astera path) + `KnockbackApplied` if knockback modifier was in
   the rule.
3. Trigger 2 cooldowns simultaneously ⇒ both `CooldownExpired` events
   fire on the same tick; HUD listener updates without polling.

## References

- [[adr/0022-multi-plugin-architecture]] — sibling-isolation
  (combat → core + items only)
- [[adr/0026-paper-runtime-topology]] — Wave 6a Paper sweep umbrella
- [[adr/0072-wave-6-paper-impl-allow-list]] — sibling allow-list
  shrink precedent (Wave 6a-N1 + this wave)
- [[adr/0149-astera-internal-hp-death-model]] — §C source-of-truth
  invariant honoured by all three Paper components
- [[adr/0151-paper-adapter-rewrite-astera-owns-damage]] — Wave 6a-3
  Paper sweep precedent (`appliedThisTick` marker pattern)
- [[adr/0152-paper-adapter-batch-food-movement-vision-camera-sign]] —
  Listener-shaped Paper adapter pattern (PaperPlayerFoodGateway §A)
- [[adr/0153-bukkit-event-adapter]] — Role 2 vanilla-intrusion
  handler that catches non-Astera projectile damage
- [[adr/0154-match-timer-service]] — companion Wave 6a-N1 (pure-
  Kotlin allow-list shrink precedent)
