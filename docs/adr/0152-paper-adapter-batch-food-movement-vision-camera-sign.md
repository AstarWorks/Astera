---
status: Accepted
date: 2026-06-02
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0152: Paper adapter batch — food + movement + vision + camera + sign

## Status

Accepted. Ships 5 Paper-side gateways across two Wave 6a sub-waves
(`6a-5` food + `6a-7` movement / vision / camera / sign). Companion ADR
[[adr/0153-bukkit-event-adapter]] ships the BukkitEventAdapter from
Wave 6a-6 in the same commit.

## Context

Wave 6a Paper sweep landed `PaperKnockbackGateway` (ADR-0144),
`PaperPlayerHealthGateway` (ADR-0145 → superseded by ADR-0151),
`PaperDamageGateway` (ADR-0146 → superseded by ADR-0149), and
`PaperPlayerSpeedGateway` (ADR-0147) across sub-waves 6a-1 .. 6a-4.

Five ports remained with `InMemory` impls only or no impl at all:

- `IPlayerFoodGateway` — InMemory at priority 0 (Wave 5e ADR-0058 family).
- `IPlayerMovementGateway` — port-only (Wave 5g; no impl shipped).
- `IPlayerVisionGateway` — port-only (Wave 5g; no impl shipped).
- `ICameraGateway` — InMemory at priority 0 (Wave 5cinematic ADR-0110).
- `ISignActionService` — InMemory at priority 0 (Wave 5block-embed).

This ADR ships the Paper concretes for all five at priority 100 in a
single batch.

## Decision

### §A — `PaperPlayerFoodGateway` (Wave 6a-5)

`plugin/core/src/main/kotlin/.../adapter/paper/physiology/PaperPlayerFoodGateway.kt`,
246 LOC.

Constructor: `(server: Server, scheduler: IScheduler, eventBus: IDomainEventBus)`.

**First Paper adapter in Astera that implements `org.bukkit.event.Listener`.**
The pattern is now precedent for any future Paper gateway that needs
event-cancellation as part of its contract (the `BukkitEventAdapter` from
ADR-0153 ships in the same commit and follows the same shape).

Lifecycle:
- Constructor is side-effect-free (just stores deps + initialises
  internal `suppressed: MutableSet<PlayerId>`).
- `start(plugin: JavaPlugin)` registers self via
  `plugin.server.pluginManager.registerEvents(this, plugin)`. Called
  exactly once from `AsteraCorePlugin.onEnable` immediately after
  construction and before `services.register`.
- Bukkit `HandlerList.unregisterAll(plugin)` reclaims the registration
  automatically on plugin disable — no explicit cleanup in `onDisable`.

Event handler:
```kotlin
@EventHandler(priority = HIGH, ignoreCancelled = true)
fun onFoodLevelChange(event: FoodLevelChangeEvent) {
    if (event.entity !is Player) return
    if (PlayerId(entity.uniqueId) !in suppressed) return
    event.isCancelled = true
    entity.foodLevel = MAX_FOOD_LEVEL          // belt-and-suspenders
    entity.saturation = MAX_SATURATION
}
```

Belt-and-suspenders writes guard against the cancellation losing a race
with another HIGH-priority handler that already mutated the field.

The 7 IPlayerFoodGateway methods all check `server.getPlayer(uuid)`
first; null ⇒ `Result.failure(FoodMutationError.BackendUnavailable)`
(matches `PaperPlayerSpeedGateway` shape, ADR-0147).

### §B — Player physiology / cinematic / sign batch (Wave 6a-7)

Four adapters under `plugin/core/.../adapter/paper/{physiology,cinematic,sign}/`.

**`PaperPlayerMovementGateway`** — 134 LOC. Sole concern: gravity
multiplier with TTL.
- Bukkit map: `player.getAttribute(Attribute.GRAVITY).baseValue =
  GRAVITY_BASELINE * multiplier` where `GRAVITY_BASELINE = 0.08`
  (vanilla 1.21+).
- TTL: `scheduler.runLaterTicks(ttl) { resetGravity }` per the
  `PaperPlayerSpeedGateway` precedent (single timer, scheduler-owned).
- Input safety: non-finite or negative multiplier ⇒ clamp to `1.0` +
  DEBUG log. Port returns `Unit` (no `Result`) per its KDoc contract.
- Paper 1.21+ only — earlier Paper builds lack `Attribute.GRAVITY` and
  silent-skip with DEBUG log.

**`PaperPlayerVisionGateway`** — 135 LOC. Sole concern: fog radius +
night-vision toggle.
- Bukkit map: `PotionEffect(PotionEffectType.BLINDNESS, ticks,
  amplifier=0, ambient=false, particles=false, icon=true)`.
- TTL: **delegated to Bukkit potion lifetime**, not Astera scheduler.
  Rationale: avoid two-timer desync. The port's state map records the
  requested radius for the getter; Bukkit drives the actual expiry.
- Limitation: vanilla BLINDNESS is binary — per-player fog-radius
  remains unenforced until Wave 6b's packet adapter lands the
  `ClientboundUpdateMobEffectPacket` overlay. The radius parameter
  is honoured in the internal state map (for `getFogRadius` reads)
  but has no visual effect.

**`PaperCameraGateway`** — 286 LOC. Sole concern: third-person /
spectator camera control.
- Bukkit map for entity / player targets:
  `player.setSpectatorTarget(entity)` (Bukkit native API).
- `freeze(player, ticks)` ⇒ self-spectator (`setSpectatorTarget(player)`)
  + `scheduler.runLaterTicks(ticks) { releaseCamera }` which publishes
  `CameraReleased` per port KDoc.
- **Phase 1 stubs** (return Success with no visual; INFO log once per
  server lifetime):
  - `lockCamera(viewer, Position)` — needs `ClientboundSetCameraPacket`
    + entity-less Position-anchored camera (Wave 6b).
  - `lockCamera(viewer, RelativeToPlayer)` — needs camera-pose math
    (Wave 6b).
  - `panCamera` — needs `ClientboundPlayerRotationPacket` easing curve
    (Wave 6b).
  - `shakeCamera` — needs RNG-driven pose jitter packets (Wave 6b).
  - `zoomCamera` — needs FOV-modifier packet (Wave 6b).
- GameMode coupling: `setSpectatorTarget` only works when the viewer is
  in `SPECTATOR`. Callers sequence with `ISpectatorGateway` (Wave 5h);
  out-of-mode callers see no effect (Bukkit returns silently).

**`PaperSignActionService`** — 161 LOC. Sole concern: per-block sign
action binding.
- Bukkit map: `world.getBlockAt(x, y, z).state is Sign` advisory check
  at `bindSignAction` time (DEBUG log on world-unloaded / non-sign
  block — binding still recorded; preserves YAML-preload flow where
  the world / chunk isn't loaded yet).
- Lookup / unbind are pure map ops (JVM-lifetime; no persistence —
  preserves the InMemory shape).
- Coordinate floor: `floor(position.x).toInt()` (matches Bukkit
  `Location.getBlockX()` for negative coords).

### §C — `AsteraCorePlugin` wiring

All five adapters wire **after** the existing InMemory priority-0
registrations (where applicable), at priority 100 for consistency with
the Wave 6a-1 .. 6a-4 pattern. The food gateway calls `start(this)`
between construction and `services.register` so the Bukkit Listener is
live before any downstream `services.get<IPlayerFoodGateway>()` lookup.

No `onDisable` cleanup is required: food gateway's Listener is reclaimed
by `HandlerList.unregisterAll(plugin)`; the four other gateways own no
long-lived state besides scheduler-owned task handles that the scheduler
cancels on its own shutdown.

### §D — Out-of-scope (Wave 6b)

The Phase 1 stubs documented in §B are explicit deferrals — the
constructor + handle bookkeeping + input validation are correct; only
the Bukkit visual fidelity is stub. Wave 6b lands the custom-packet
adapter that supplies the missing visual surface:

- Position-anchored / relative camera locks
- Pan / shake / zoom (Camera)
- Per-player BLINDNESS fog-radius (Vision)
- BLINDNESS icon-suppression (Vision)
- Sign blockstate strict validation + `SignChangeEvent` bridge (Sign)

## Consequences

### Positive

- 5 ports move from InMemory-only / port-only to Paper-backed at
  priority 100 in a single commit. The `services.get<I*>()` lookup
  surface is unchanged (priority 100 transparently wins over priority
  0) so existing callers compile without modification.
- The Listener-shaped Paper-adapter pattern is now precedent
  (`PaperPlayerFoodGateway` + ADR-0153's `BukkitEventAdapter` both
  follow the same shape). Future event-cancelling adapters use this
  template directly.
- No new Konsist rules required — every import in the new files is
  already permitted by the existing `core.adapter.paper.*` boundary
  (Konsist Rules 1–8 unaffected).

### Neutral / costs

- Phase 1 stubs in `PaperCameraGateway` (5 methods) + `PaperPlayerVisionGateway`
  (radius parameter unenforced) ship with their input-validation correct
  but no visual fidelity. Callers can sequence against the port API today
  and gain the visuals automatically when Wave 6b's packet adapter
  registers at priority 200.

### Negative

- `PaperPlayerMovementGateway` requires Paper 1.21+ for
  `Attribute.GRAVITY`. Earlier Paper builds silent-skip with DEBUG log
  — acceptable for the Phase 1 PoC target environment.
- `PaperPlayerVisionGateway`'s TTL is Bukkit-owned (PotionEffect
  duration) rather than Astera-scheduler-owned, diverging from the
  `PaperPlayerSpeedGateway` precedent. The divergence is deliberate —
  preventing two-timer desync is more valuable than precedent
  symmetry — but readers should note the asymmetry.

## Verification

- `./gradlew :plugin:core:compileKotlin` green.
- `./gradlew :tools:architecture-test:test` green (no new Konsist rules
  required; existing rules unaffected by the new files).
- `./gradlew check` green.

## References

- [[adr/0022-multi-plugin-architecture]] — sibling-isolation
- [[adr/0144-paper-knockback-gateway]] — Wave 6a-1 precedent
- [[adr/0147-paper-player-speed-gateway]] — Wave 6a-4 precedent (sibling shape)
- [[adr/0149-astera-internal-hp-death-model]] — Wave 6a-redesign Step 1
- [[adr/0150-hp-gateway-container-migration]] — Wave 6a-redesign Step 2
- [[adr/0151-paper-adapter-rewrite-astera-owns-damage]] — Wave 6a-redesign Step 3
- [[adr/0153-bukkit-event-adapter]] — companion ADR (Wave 6a-6)
- [[adr/0058-universal-attribute-container]] — Wave 5e attribute substrate
- [[adr/0110-cinematic-foundation]] — Wave 5cinematic camera port
