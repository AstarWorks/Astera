---
status: Accepted
date: 2026-05-27
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0113: Player + Entity + Block animation foundation (Wave 5animation)

## Status

Accepted (Wave 5animation delivery, 2026-05-27)

## Context

The 2026-05-27 Round 6 audit (`/home/node/.claude/plans/wave-5g-core-hardening-giggly-sunset.md`
§"Round 5 + Round 6 — Effect / Cinematic / Event / Animation / GUI depth")
flagged a large gap in Astera's pre-Paper foundation:

- **Wave 5h Hologram dynamic update** only touches Display entities —
  every other in-game animation surface (player swing / equip / sneak /
  sleep / sit, mob attack / death / pickup / damage flash / drown / look,
  block break cracked-stage overlay, mining progress UI) had **no Core
  port and no vendor-neutral domain vocabulary**.
- Wave 5combat-depth's combo finisher needs to fire vanilla swing
  animations from skill-engine output.
- Wave 5pve-boss attack patterns need `playMobAttackAnimation` to drive
  scripted combat sequences without coupling to Bukkit.
- Wave 5x BlockState durability tracks block HP but does **not** drive
  the player-visible "cracked block" overlay — vanilla shows that via
  per-position `BlockBreakAnimationPacket`, which requires its own
  per-position state.
- Wave 5j HUD foundation registers an `IHudGateway` interface but the
  Core plugin never wired the in-memory impl, and there was no thin
  domain wrapper for "the mining progress bar" — every mining loop
  would build a `HudComponent` tree inline.

The Wave 6 Paper sweep is "wrap the API only" (per the wave plan's
"Wrap" / "Skip" table). If the domain ports these adapters publish
through are missing, Wave 6 must grow the domain itself, which
violates the sweep's intent and forces a multi-wave back-and-forth.

Pre-release posture (CLAUDE.md §"Pre-release posture (Phase 1 PoC
closing)") means we can land the foundation as breaking-change-friendly
ports and types now, rather than design around hypothetical existing
callers. Phase 1 has no users to migrate; the cost of "land
foundation now, retrofit later" is zero in this window.

## Decision

Land a **player + entity + block animation foundation** across
`:plugin:core` with these shapes. **No Paper adapter** (deferred to
Wave 6); **no DB migration** (animation state is session-scoped, never
persisted).

### Core domain — animation vocabulary (`core.domain.animation.*`, NEW)

Pure-Kotlin types backing the new ports:

**`player/` subpackage**:
- **`PlayerHand`** — enum `MainHand / OffHand`. Vendor-neutral mirror
  of Bukkit `EquipmentSlot.{HAND, OFF_HAND}` scoped to the swing domain
  (the broader `EquipSlot` carries armor variants that have no swing
  animation).
- **`SitTarget`** — sealed: `Vehicle(EntityHandle)`, `Position(Vec3)`,
  `None`. Lets the Paper adapter choose between `Player.addPassenger`
  (Vehicle), synthesised armor-stand (Position), and
  `Player.leaveVehicle` (None) at the boundary.
- **`CustomAnimationId`** — `JvmInline value class` lower-kebab-case id
  for the Phase 4 cosmetic emote system. Wave 5animation ships the id
  type only; registry comes with the emote feature.
- **`PlayerAnimationError`** — sealed: `UnknownPlayer`,
  `NoActiveAnimation`, `UnknownCustomAnimation`, `UnknownItem`,
  `IncompatibleEquipSlot`, `InvalidDuration`, `Platform`. Covers every
  failure mode of `IPlayerAnimationGateway` without crossing the
  raw-throwable boundary (Konsist rule "application layers must not
  import raw JVM throwable types").

**`entity/` subpackage**:
- **`MobAnimationKind`** — sealed: `Attack`, `Death`, `Pickup(item)`,
  `DamageFlash`, `Drown(drowning)`, `LookAt(direction)`. One sealed
  family covers every animation variant; per-variant events would
  inflate the event class count and force every subscriber to register
  N handlers.
- **`EntityAnimationError`** — sealed: `UnknownEntity`,
  `InvalidDirection`, `Platform`.

**`block/` subpackage**:
- **`BlockBreakStage`** — enum: `STAGE_0 .. STAGE_9` (vanilla 10
  stages) + `RESET`. The progress-to-stage mapping is owned by the
  caller (linear sweep vs HP-bucket-skewed), not the enum.
- **`BlockBreakAnimationError`** — sealed: `Platform`.

**`mining/` subpackage**:
- **`MiningProgressContext`** — value record `(playerId, blockPosition,
  currentHp, maxHp, toolType, etaTicks)` with `init`-validated
  invariants. Exposes `hpFraction` for renderer-friendly progress.
- **`MiningCancelReason`** — sealed: `ReleasedInput`, `SwitchedTarget`,
  `SwitchedTool`, `OutOfRange`, `SystemStopped(detail)`.

### Core cross-plugin events (`core.domain.event.*`)

Per CLAUDE.md "sealed `DomainEvent` hierarchy lives in `plugin:core`",
all nine new events live here:

- **`PlayerSwungArm(playerId, hand, atTick)`** — every gateway `swingArm`
  publishes; skill-trigger router subscribes for left-click skill
  bindings.
- **`PlayerEquipped(playerId, slot, item, atTick)`** — distinct from
  `PlayerActiveWeaponChanged` (hotbar scroll); fires on deliberate
  `applyEquipAnimation` calls.
- **`PlayerSneakChanged(playerId, sneaking, atTick)`** — gateway dedupes
  on no-change so subscribers see only real transitions.
- **`PlayerSleepChanged(playerId, world, position, sleeping, atTick)`**
  — wraps bed-enter / wake-up with world + position context.
- **`PlayerSitChanged(playerId, target, atTick)`** — target carries the
  sealed sit variant (Vehicle / Position / None).
- **`MobAnimationPlayed(handle, kind, atTick)`** — single event with
  `MobAnimationKind` discriminator covers all 6 mob animation variants.
- **`BlockBreakStageChanged(world, position, previousStage, newStage,
  viewers, atTick)`** — gateway dedupes redundant stage assertions.
  `viewers = null` means broadcast to all viewers in render distance.
- **`MiningStarted(playerId, world, blockPosition, toolType, atTick)`**
  — fires on first per-tick update for a (player, block) pair.
- **`MiningCompleted(playerId, world, blockPosition, elapsedTicks,
  atTick)`** — `init`-validated `elapsedTicks >= 0`.
- **`MiningCancelled(playerId, world, blockPosition, reason, atTick)`**
  — reason discriminator distinguishes player vs system cancellation.

### Core outbound ports

Four new vendor-neutral interfaces:

- **`IPlayerAnimationGateway`** (`core.application.port.outbound.animation.*`)
  — `swingArm`, `applyEquipAnimation`, `setSneaking`, `setSleeping`,
  `setSitting`, `playCustomAnimation`. Every fallible method returns
  `Result<Unit, PlayerAnimationError>`. State-mutation methods
  (`setSneaking` / `setSleeping` / `setSitting`) are idempotent —
  re-asserting the current state returns Success without publishing
  an event. One-shot methods (`swingArm` / `applyEquipAnimation` /
  `playCustomAnimation`) always publish on success.
- **`IEntityAnimationGateway`** — six methods mirroring the
  `MobAnimationKind` variants. `setMobLookDirection` validates that
  the direction is non-zero and finite, then normalises before
  publishing.
- **`IBlockBreakAnimationGateway`** (`core.application.port.outbound.block.*`)
  — `setBreakStage(... broadcastViewers = null)` for per-viewer
  partitioning, `cancelBreakAnimation`, `getCurrentStage`. Dedupes
  per (world, position) — calling `setBreakStage(STAGE_3)` twice in
  a row returns Success without a second event publish.
- **`IMiningProgressDisplay`** — `update(playerId, context)` and
  `clear(playerId)`. Thin wrapper over `IHudGateway` that lives in
  its own port so mining-system tests can use a focused
  `FakeMiningProgressDisplay` instead of asserting on HudComponent
  trees.

### Why a separate `IBlockBreakAnimationGateway` port (not extend `IBlockStateGateway`)

The Wave 5x `IBlockStateGateway` owns the durability **state** (HP,
max-HP, last-damaged tick). The new animation gateway owns the
**visual overlay** (the 10-stage cracked-block texture). These are
deliberately separate ports because:

1. **Lifecycle mismatch**: state is per-position-permanent until
   restoration; animation is ephemeral and per-viewer-customisable.
2. **Independent firing**: skill damage doesn't always show a crack
   (instant-break skills skip stages); cracked overlays sometimes
   appear without state change (visual hint for mining HUD).
3. **Wave 6 adapter shape**: state wires to NBT + Postgres; animation
   is a packet-only edge with no persistence.

### Why a single `IPlayerAnimationGateway` (not 6 ports)

Player animation operations share the same observable contract (event
publish on success, no return state beyond ack), and Wave 6 will wire
them all to the same Bukkit-side adapter. A single port keeps the
`AsteraCorePlugin` wiring lean — one `services.register` line per
gateway instead of six.

### Core in-memory impls (`core.application.service.animation.*`)

Phase 1 implementations, drop-in-overridable at priority 100 by the
Wave 6 Paper adapter:

- **`InMemoryPlayerAnimationGateway`** — tracks sneak / sleep / sit
  state in `ConcurrentHashMap`s, dedupes on no-change, publishes
  events through the constructor-injected `IDomainEventBus`.
- **`InMemoryEntityAnimationGateway`** — does not track entity
  liveness (handle staleness is the entity-spawner's concern; the
  Wave 6 adapter silently ignores stale handles); validates
  look-direction vector for non-zero + finite.
- **`InMemoryBlockBreakAnimationGateway`** — per-(world, position)
  tracker with dedup. `RESET` removes the tracker entry; explicit
  `cancelBreakAnimation` publishes with `newStage = RESET`.
- **`InMemoryMiningProgressDisplay`** — assembles a `HudComponent.Composite`
  of title `Text` + `ProgressBar` and routes through `IHudGateway`.
  First `update` issues `showFor`; subsequent calls issue `updateFor`
  on the same handle; `clear` calls `clearFor(playerId, ACTION_BAR)`.

### Test-fixtures Fakes

`plugin:test-fixtures` ships matching Fakes for all four ports:
`FakePlayerAnimationGateway` / `FakeEntityAnimationGateway` /
`FakeBlockBreakAnimationGateway` / `FakeMiningProgressDisplay`. Each
exposes a typed `operations` list for assertions and an optional
`eventBus` constructor parameter for tests that also want to verify
event publication.

### Wiring (`AsteraCorePlugin.onEnable`)

Five new `services.register<...>(impl, priority = 0)` lines, placed
after the Wave 5cinematic camera+music block:

```
services.register<IPlayerAnimationGateway>(InMemoryPlayerAnimationGateway(eventBus), 0)
services.register<IEntityAnimationGateway>(InMemoryEntityAnimationGateway(eventBus), 0)
services.register<IBlockBreakAnimationGateway>(InMemoryBlockBreakAnimationGateway(eventBus), 0)
// Lazy-wire IHudGateway if not already registered (Wave 5j gap fill)
val hud = services.get<IHudGateway>() ?: InMemoryHudGateway(eventBus).also {
    services.register<IHudGateway>(it, priority = 0)
}
services.register<IMiningProgressDisplay>(InMemoryMiningProgressDisplay(hud), 0)
```

The Wave 5j `IHudGateway` was previously declared but never wired in
`AsteraCorePlugin` — Wave 5animation closes that gap so the mining
display has a non-null port to compose with. Wave 6b's
`PaperHudGateway` lands at priority 100 and shadows.

### Konsist guards (+1 rule, total 51)

- **`animation domain stays pure (ADR-0113)`** —
  `core.domain.animation.*` (incl. `player/`, `entity/`, `block/`,
  `mining/` subpackages) must not import Bukkit family,
  kotlinx.serialization, JDBC, Exposed, postgres, lettuce, or Koin.

## Consequences

### Positive

- **Wave 6 Paper sweep stays "wrap-only"** — the domain events the
  Paper-side `BukkitAnimationAdapter` will publish are already
  declared. The adapter writes `Player.swingMainHand()` glue without
  growing the domain.
- **Skill engine unblocked** — Wave 5combat-depth combo finisher can
  call `IPlayerAnimationGateway.swingArm` from skill output without
  reaching into Bukkit; Wave 5pve-boss attack patterns can drive
  scripted swings via `IEntityAnimationGateway.playMobAttackAnimation`.
- **Mining UX coherent end-to-end** — `IBlockBreakAnimationGateway`
  drives the world-side cracked overlay, `IMiningProgressDisplay`
  drives the player-side HUD, both publish events the audit-log /
  quest / stats subsystems already subscribe to. One coherent picture
  in one wave.
- **HUD gateway gap closed** — `IHudGateway` is now actually wired in
  `AsteraCorePlugin` (Wave 5j shipped the impl but never wired it).
  Wave 5animation's mining display is the first real user.
- **Single mob animation event (not 6)** — `MobAnimationPlayed` carries
  `MobAnimationKind` discriminator. One subscribe call covers
  attack-only, death-only, all-of-them — subscribers `when`-switch on
  the variant.
- **Cross-plugin event consistency** — every new event follows the
  established Wave 5world-events pattern: `(payload..., atTick,
  version, occurredAt)` with validation in `init` blocks where the
  domain has invariants (`MiningCompleted.elapsedTicks >= 0`,
  `MiningProgressContext.{currentHp, maxHp, etaTicks}`).

### Negative / Trade-offs

- **`playCustomAnimation` returns Success for unknown ids in Phase 1**
  — the Phase 4 cosmetic emote registry will validate at the call site
  before invoking the gateway. The gateway accepts any
  `CustomAnimationId` (regex-validated at construction) and the actual
  catalog lookup is the caller's responsibility. Mitigated by the
  return type being `Result<Unit, PlayerAnimationError>` so the
  signature already supports the future failure mode.
- **No per-event throttling enforcement on `BlockBreakStageChanged`**
  — every distinct stage change publishes one event. A buggy mining
  loop that advances stages every server tick will publish 20 events
  per second per active miner. Mitigated by the dedup-on-equal logic
  and by the fact that the Wave 6 driver throttles to vanilla 10-stage
  cadence anyway.
- **No tracking of "currently sleeping at position X"** — the gateway
  records the boolean sleep state but not the bed position. Callers
  that need to know "where is player Y sleeping" must observe the
  `PlayerSleepChanged` event. Mitigated by the event carrying the
  position; the gateway's job is animation, not bed-occupancy lookup.
- **`InMemoryMiningProgressDisplay` re-issues `updateFor` per tick**
  with no diff-aware caching — every tick produces a new
  `HudComponent.Composite`. Mitigated by the in-memory impl being a
  no-op for the UI side anyway; the Wave 6b `PaperHudGateway` is
  expected to short-circuit when the rendered string hasn't changed.

### Mitigations

- **Konsist guard prevents domain decay** — the new rule catches the
  most likely future regression (a Paper-side adapter writer
  importing `org.bukkit.entity.Player` into `MobAnimationKind` to
  "save a conversion").
- **Sealed types ride the same convention as Wave 5world-events** —
  `SitTarget` / `MobAnimationKind` / `MiningCancelReason` are all
  `sealed interface` so future variants can carry payload without
  breaking exhaustive callers (lesson from Wave 5world-events' three
  reason-enum decisions).
- **Lazy `IHudGateway` wiring is idempotent** — if a higher-priority
  override has already registered, the lazy lookup returns it; the
  in-memory fallback only runs when no impl exists. Wave 6b can ship
  `PaperHudGateway` at priority 100 with no order-of-`onEnable` worry.

## Alternatives Considered

- **Combine player + entity + block into one giant `IAnimationGateway`**
  — rejected: the three concern groups have wildly different state
  shapes (per-player sneak booleans vs per-entity look directions vs
  per-position cracked stages). Mixing them would force every caller
  to either implement methods it doesn't care about, or live with a
  large interface area. Three focused ports cost one extra
  `services.register` line each.
- **Per-variant mob animation events (`MobAttacked`, `MobDied`,
  `MobPickedUp`, …)** — rejected: would inflate the event class count
  by 6, force every subscriber to register 6 handlers, and remove the
  `when`-on-`kind` ergonomics. The discriminator pattern matches
  Wave 5x's `BlockDamageOutcome` (sealed) and Wave 5cinematic's
  `CameraTarget` (sealed) approach.
- **Reuse `IBlockStateGateway` for break animation** — rejected: the
  state gateway owns persistence-bound HP; the animation gateway owns
  per-viewer ephemeral packets. Same-port coupling would force the
  Paper adapter to handle two unrelated concerns in one impl class.
- **Skip `IMiningProgressDisplay` and let the mining loop call
  `IHudGateway` directly** — rejected: every mining call site would
  rebuild the same `HudComponent.Composite` tree, every test would
  need to assert on Components instead of "the display was asked to
  show progress", and the Phase 4 "let players opt out of the HUD"
  toggle would have no natural home. The thin wrapper port costs one
  interface + one impl + one Fake — small price for the focus.
- **Custom-animation id as a free-form string** — rejected: the
  `JvmInline value class` with lower-kebab-case regex matches the
  established id discipline (`ItemId`, `MatchId`, `KitId`). YAML typos
  surface at construction, not at runtime when the registry returns
  null.

## References

- `plugin/core/.../domain/animation/{player,entity,block,mining}/*.kt`
- `plugin/core/.../domain/event/{PlayerSwungArm,PlayerEquipped,PlayerSneakChanged,PlayerSleepChanged,PlayerSitChanged,MobAnimationPlayed,BlockBreakStageChanged,MiningStarted,MiningCompleted,MiningCancelled}.kt`
- `plugin/core/.../application/port/outbound/animation/{IPlayerAnimationGateway,IEntityAnimationGateway,IMiningProgressDisplay}.kt`
- `plugin/core/.../application/port/outbound/block/IBlockBreakAnimationGateway.kt`
- `plugin/core/.../application/service/animation/InMemory*.kt`
- `plugin/test-fixtures/.../animation/Fake*.kt`
- `tools/architecture-test/.../ArchitectureTest.kt` (new
  `animation domain stays pure (ADR-0113)` rule)
- `.claude/plans/wave-5g-core-hardening-giggly-sunset.md` §Round 6
  (origin of the gap analysis)
- [[adr/0090-custom-block-extension-two-axis-tool-postgres-durability]]
  (the Wave 5x `BlockState` / `IBlockStateGateway` whose state-side
  vocabulary this wave's animation-side gateway sits next to)
- [[adr/0097-custom-hud-foundation]] (the Wave 5j `IHudGateway`
  finally wired in `AsteraCorePlugin` by this wave)
- [[adr/0110-camera-music-gateway-foundation]] (Wave 5cinematic that
  this wave's wiring block sits immediately after)
- [[adr/0111-world-events-and-inventory-mutation-foundation]] (Wave
  5world-events pattern of "vendor-neutral reason enums + cross-plugin
  events + InMemory impls" that this wave mirrors)
