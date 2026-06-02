---
status: Accepted
date: 2026-06-03
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0161: Wave 6d — Paper Sweep round 2 (World / Sound / AOE / Animation)

## Status

Accepted. Wave 6d adds **7 Paper adapters** for outbound ports that were
shipping with InMemory / Fake defaults only. None of these ports were on
the `allowedMissingPaperImpl` Konsist allow-set (it was emptied at Wave
6a-N3 / ADR-0159) — they were "InMemory-default acceptable for PoC" ports
whose Paper impl was deferred to a future hardening wave. Wave 6d is that
wave.

## Context

`docs/RTM-PARITY-TRACKER.md` reports ~10 outbound ports in the 🔌
("port-only, no Paper impl yet") column. Triaged by complexity, the seven
that are pure Bukkit API wrappers — no threading subtlety, no
state-machine complexity, no entity-lifecycle management beyond simple
idempotency tracking — can ship together as one wave:

| Port | Plugin | Wave-of-origin |
|---|---|---|
| `IWorldTimeGateway` | core | 5n (ADR-0080) |
| `IWorldWeatherGateway` | core | 5n |
| `IWorldRulesGateway` | core | 5n |
| `IPlayerSoundGateway` | core | 5effects (ADR-0109) |
| `ILightningGateway` | combat | 5m (ADR-0088) |
| `IFireSpreadController` | combat | 5m |
| `IPlayerAnimationGateway` | core | 5animation (ADR-0113) |

Without these adapters, every YAML / use case that calls into these ports
runs against the InMemory snapshot of state — vanilla operator commands
like `/time set day` would be invisible to the gateway, lightning strikes
would not actually appear in-world, fire-spread toggles would not affect
the live gamerule, and player animation triggers (`Player.swingMainHand`,
`Player.setSneaking`, `Player.sleep`, mount-on-armor-stand for sit) would
not actually fire on the player's client. Phase 3 Siege gameplay assumes
these are live; this wave makes them so.

The heavier Paper impls remaining (`IExplosionGateway`,
`IAreaEffectGateway`, `IHologramGateway` 16 per-type updaters, cinematic
ports) are explicitly out of scope — they each warrant their own wave
because of LOC volume, edge-case density, and gameplay-impact concentration.

## Decision

Wave 6d lands seven Paper adapters as one branch / one commit / one ADR,
with three sub-waves of worker scope mapped to four §s:

### §A — World adapters (W1, 3 files)

- **`PaperWorldTimeGateway`** (4 methods): `setTime` → `World.time =`,
  `freezeTime` → `World.setGameRule(DO_DAYLIGHT_CYCLE, !frozen)`,
  `getCurrentTime` → `World.time`, `isTimeFrozen` → read the gamerule
  (inverted). Unknown world → null/no-op (matches InMemory's "absent" semantic).
- **`PaperWorldWeatherGateway`** (4 methods): `setWeather` maps
  `WeatherState.Clear/Rain/Thunder` to `World.setStorm(bool)` +
  `setThundering(bool)` combinations; `freezeWeather` toggles
  `DO_WEATHER_CYCLE`; `getCurrentWeather` reads `isThundering` and
  `hasStorm` (thunder takes precedence per vanilla semantics).
- **`PaperWorldRulesGateway`** (14 methods, 7 setters + 7 getters):
  routes each rule (KEEP_INVENTORY / DO_MOB_SPAWNING / DO_FIRE_TICK /
  DO_WEATHER_CYCLE / DO_DAYLIGHT_CYCLE / PVP / difficulty) through the
  matching Bukkit API. `WorldDifficulty` enum maps 1:1 to
  `org.bukkit.Difficulty`.

All three are constructed with `(server: Server)` only — no domain
collaborators, no scheduler. Off-main calls log WARN + drop (no `Result`
return on these ports).

### §B — Effect adapter (W2, 1 file)

- **`PaperPlayerSoundGateway`** (1 method): `playForViewer(viewer, sound,
  position?, world?)` resolves a `Player` via `server.getPlayer(uuid)`,
  builds a Location (fallback `player.location` if position+world not
  supplied), and calls
  `player.playSound(Location, soundKey, SoundCategory.MASTER, volume.toFloat(), pitch.toFloat())`.
  The `String`-key overload is chosen over the `Sound` enum so
  data-pack-defined sounds and custom resource-pack entries work
  uniformly. Off-line viewer → silent no-op (port is fire-and-forget).

### §C — AOE adapters (W2, 2 files)

- **`PaperLightningGateway`** (1 method): `strike(world, position,
  damageEntities)` resolves a Bukkit world, builds a `Location`, calls
  `world.strikeLightning(location)` when `damageEntities=true` (vanilla
  lethal), `world.strikeLightningEffect(location)` when false
  (visual-only). Publishes `LightningStrike` domain event mirroring
  `InMemoryLightningGateway`. Off-main: defers via
  `IScheduler.runLater(Duration.ZERO, ...)` and returns
  `Result.success(Unit)`; the port's `LightningError` sealed surface is
  unchanged (`WorldNotLoaded` + `NoImplementationBound`).
- **`PaperFireSpreadController`** (3 methods): `setFireSpread` →
  `World.setGameRule(DO_FIRE_TICK, ...)`. `isFireSpreadEnabled` reads
  the same gamerule. `spreadFire(world, source, radius)` walks blocks
  within a spherical radius (`dx² + dy² + dz² ≤ r²`), capped at
  `RADIUS_MAX = 16` (`(2·16+1)³ ≈ 36k` blocks max), ignites AIR cells via
  `block.type = Material.FIRE`, returns the count of placed fires.
  Non-AIR cells skipped (flammable blocks burn-down via vanilla
  DO_FIRE_TICK after ignition; this method intentionally only handles
  the initial seed). Off-main returns 0 + WARN.

### §D — Animation adapter (W3, 1 file + 1 test)

- **`PaperPlayerAnimationGateway`** (8 methods + idempotency contract):
  - One-shot triggers: `swingArm(viewer, hand)` →
    `Player.swingMainHand()` / `swingOffHand()` per hand enum;
    `applyEquipAnimation(viewer, slot, item)` publishes `PlayerEquipped`
    event but **does NOT mutate inventory** (per port KDoc: the use case
    owns inventory writes; this port is animation-only); `playCustomAnimation`
    validates duration ≥ 0 then is a Phase 1 stub (debug-log + Platform-failure
    return on success path) since vanilla Bukkit has no packet hook for
    arbitrary custom animations.
  - Stateful (idempotent): `setSneaking` / `setSleeping` / `setSitting`
    track the last-applied state in three `ConcurrentHashMap<PlayerId, ...>`
    fields. Repeat-call short-circuits to `Result.success(Unit)` without
    re-issuing the Bukkit mutation; only real transitions publish the
    matching `PlayerSneakChanged` / `PlayerSleepChanged` / `PlayerSitChanged`
    domain event. Sit with `SitTarget.Position` synthesizes an invisible
    marker `ArmorStand` (gravity-disabled, no-base-plate, marker=true,
    small=true) and mounts the player; the stand's UUID is tracked and
    auto-removed when sit transitions out of `Position` (including
    `Position → other Position` replacement and `Position → None`).
  - Error variants used: `UnknownPlayer` (offline viewer),
    `Platform("off-main")` (off-main on any mutator),
    `Platform("world-not-loaded")` (sleep with unloaded world),
    `Platform("vehicle-entity-not-found")` (sit Vehicle target not in
    server), `InvalidDuration` (custom animation with negative duration).
    Other variants on `PlayerAnimationError` (`NoActiveAnimation`,
    `UnknownCustomAnimation`, `UnknownItem`, `IncompatibleEquipSlot`) are
    not raised by the Paper adapter — matches InMemory SSOT, which also
    skips registry-aware checks at Phase 1.
- 5 smoke tests using `java.lang.reflect.Proxy` for the `Server` /
  `Player` test doubles (Mockito not on `:plugin:core` test classpath).

### Phase C wiring

- `AsteraCorePlugin.onEnable`:
  - World ports: priority-100 registration of `PaperWorldTimeGateway` /
    `PaperWorldWeatherGateway` / `PaperWorldRulesGateway`, immediately
    after the existing priority-0 InMemory registrations (Wave 5n).
  - `IPlayerSoundGateway`: priority-100 registration of `PaperPlayerSoundGateway`
    after the existing `LoggingPlayerSoundGateway` @ priority 0 (Wave 5effects).
  - `IPlayerAnimationGateway`: BOTH InMemoryPlayerAnimationGateway @ p=0
    AND PaperPlayerAnimationGateway @ p=100 freshly registered (the InMemory
    was shipped at Wave 5animation but never registered before Wave 6d —
    this wave closes that gap). `tickClock = scheduler::currentTick` so
    events carry real server ticks.
- `AsteraCombatPlugin.onEnable`:
  - `ILightningGateway`: priority-100 registration of `PaperLightningGateway`
    after the existing priority-0 InMemoryLightningGateway (Wave 5m).
  - `IFireSpreadController`: priority-100 registration of
    `PaperFireSpreadController` after the existing priority-0 InMemory impl.

## Consequences

### Positive

- Seven RTM rows flip from 🔌 → ✅. Estimated parity tick: +~2-3% (small
  per-row weight; high cumulative trust signal that the Paper sweep is
  not stalled).
- `/time set <ticks>` and `/weather rain` operator commands are now visible
  to Astera's domain through the Paper-overlay path. Match-mode scripts
  that set per-match time / weather actually apply in-world.
- Lightning strikes from skill content YAML actually appear in-world.
  `damageEntities` flag now meaningfully controls lethal vs FX-only.
- Fire-spread gamerule + manual ignition primitives are Paper-live —
  enabling FX-only burning patches without enabling vanilla world-wide
  fire-tick spread.
- Player animation (sneak / sleep / sit / arm-swing / equip-animation)
  now actually fires on player clients. The InMemory-mirror idempotency
  contract holds 1:1 on Paper — use cases that depend on "repeat
  setSneaking(true) only publishes one event" still observe the same
  behavior whether running against the Paper adapter or the Fake.
- `IPlayerAnimationGateway` was never registered before this wave; Wave
  6d closes the Wave 5animation wiring gap as a side effect.

### Negative / Trade-offs

- **`playCustomAnimation` remains a Phase 1 stub.** No vanilla packet hook
  exists for arbitrary client-side animations; full support requires
  packet manipulation (PacketEvents, ProtocolLib) which is out of scope.
  Custom animation triggers log WARN + return Platform-failure on the
  Paper adapter (the InMemory equivalent silently succeeds for test
  ergonomics; the Paper adapter is honest about the platform gap).
- **`PaperPlayerAnimationGateway.setSitting(Position)` creates synthetic
  ArmorStand entities.** These are tracked + cleaned up on transition,
  but if the JVM crashes between mount and cleanup the stand leaks. A
  startup-time janitor that scans for unparented marker stands would
  recover this; deferred.
- **`PaperFireSpreadController.spreadFire` does not respect MOB_GRIEFING
  or per-region permission rules.** A caller that wants region-aware
  ignition must filter the source position against `IRegionPermissionService`
  before calling. Documented in the adapter KDoc; a future
  region-aware variant could be added orthogonally.
- **Tests are smoke-level only.** No Bukkit integration testing in this
  wave (e.g., MockBukkit / Paper-test-harness). The Paper adapter
  contract is verified by-eye against the port + InMemory SSOT; full
  in-server validation lands at Phase 1 PoC end-to-end smoke testing.

### Mitigations

- All seven adapters are independently revertable file-by-file. A
  regression on one (e.g., `PaperWorldRulesGateway` mis-mapping a Bukkit
  gamerule key) does not require backing out the others.
- The InMemory defaults stay registered at priority 0 — if a Paper
  adapter throws or behaves unexpectedly on a specific Bukkit build,
  operators can disable just the priority-100 registration without
  losing the port functionality.
- Konsist remains green at 58 rules — no new rule additions, no allow-set
  changes. The adapters live entirely in `*.adapter.paper.*` (the only
  package allowed to import the Bukkit family).

## Alternatives Considered

- **Spawn 7 separate sub-waves (6d-1 … 6d-7)**: rejected. Each adapter is
  small enough that the overhead of 7 ADRs + 7 commit messages would
  exceed the implementation work. One ADR with four §s is the right
  granularity.
- **Use `Sound` enum instead of String-key for `PaperPlayerSoundGateway`**:
  rejected. `SoundSpec.key` is a String (data-pack / resource-pack friendly)
  — narrowing to the enum would lose support for custom sounds the content
  authoring scope wants to expose.
- **Implement `IExplosionGateway` Paper impl in this wave**: rejected.
  Explosion mechanics intersect with `IBlockStateGateway` (block destruction
  routing), `IBlockChangeJournalRepository` (durable removal — Wave 6c §B),
  per-target distance falloff math, and the `INearbyTargetQuery` cache —
  not a Bukkit 1:1 wrap. Wave 6e-explosion is the right slot.
- **Make `playCustomAnimation` use PacketEvents directly**: rejected. The
  PacketEvents dependency is not yet committed to `gradle/libs.versions.toml`
  (Wave 6c-stub-packetevents holds the discussion). Adding it here would
  expand scope to dependency-management + would force the HUD per-viewer
  locale follow-up to land at the same time.

## Out of scope (deferred)

- **`IExplosionGateway` Paper impl** (~500 LOC): Wave 6e-explosion.
- **`IAreaEffectGateway` Paper impl** (~250 LOC): Wave 6e adjacent.
- **`IHologramGateway` 16 per-type updater Paper impls** (Wave 5transitions,
  ADR-0115): Wave 6f-hologram-paper.
- **Cinematic / Camera / Music Paper impls** (Wave 5cinematic, ADR-0110):
  Phase 4 scope.
- **GUI animation / Modal / Tooltip Paper impls** (Wave 5gui-depth,
  ADR-0114): Phase 4 scope.
- **PacketEvents-backed enhancements** (HUD per-viewer locale, custom
  animation packet hook): blocked on PacketEvents dep decision.
- **`PaperPlayerAnimationGateway` post-crash ArmorStand janitor**:
  enhancement, deferred.

## References

- [[adr/0080-player-physiology-resource-pool]] — Wave 5n source of the
  three world gateways (`IWorldTime/Weather/Rules`).
- [[adr/0088-physical-effects-aoe-foundation]] — Wave 5m source of
  `ILightningGateway` + `IFireSpreadController`.
- [[adr/0109-effect-orchestration-foundation]] — Wave 5effects source of
  `IPlayerSoundGateway`.
- [[adr/0113-player-animation-foundation]] — Wave 5animation source of
  `IPlayerAnimationGateway`.
- [[adr/0159-sibling-paper-adapters-allowlist-closure]] — Wave 6a-N3, the
  ADR that emptied the `allowedMissingPaperImpl` allow-set and set the
  baseline for "no required Paper impl is missing; from now on Paper
  impl additions are opt-in coverage."
- [[adr/0160-paper-production-hardening]] — Wave 6c, the immediately
  preceding wave that closed production-quality gaps in already-Paper-impl
  adapters (broadcaster JSON / Discord rate-limit / block journal /
  Spectator / barrier multi-world).
- `docs/RTM-PARITY-TRACKER.md` — 7 rows flip 🔌 → ✅ as a result of
  this wave.
