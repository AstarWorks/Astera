---
status: Accepted
date: 2026-05-27
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0111: World event expansion + Inventory mutation primitives (Wave 5world-events)

## Status

Accepted (Wave 5world-events delivery, 2026-05-27)

## Context

The 2026-05-27 Round 5+6 audit (`/home/node/.claude/plans/wave-5g-core-hardening-giggly-sunset.md`
§"Round 5 + Round 6 — Effect / Cinematic / Event / Animation / GUI depth") identified
two large gaps in Astera's pre-Paper foundation:

### Gap 1: world event surface

Before this wave, `core.domain.event.*` carried block-side coverage
(`BlockPlaced`, `BlockBroken`, `BlockBrokenBySkill`, `CustomBlockPlaced`)
plus lifecycle hooks (`PlayerJoinedServer`, `PlayerQuitServer`,
`PlayerArrivedOnServer`, `PlayerRespawned`). Every spatial / interaction
signal that a region permission service (Wave 5n), anticheat (Wave 5q),
skill-trigger router (Wave 5a), or replay capture (Wave 5w) needs to react
to was **missing**:

  - Player position change (walk / run / fall) — region boundary triggers
    cannot fire without it.
  - Player teleport (command / portal / respawn / pearl / match transition)
    — needs to be distinct from "moved" because the cause changes the
    correct downstream reaction.
  - Player ↔ block interact (right click / left click / pressure plate) —
    skill-trigger by-block-click needs this; custom block listener
    (Wave 5x) cannot fire without it.
  - Chunk load / unload — spatial index + entity registry + custom block
    durability journal all need it.
  - World weather + time change — HUD layer + mob spawner + match anomaly
    system all need it.

The Wave 6 Paper sweep is "wrap the API only" (per the wave plan's
"Wrap" / "Skip" table). If the domain events these adapters publish are
missing, Wave 6 has to grow the domain itself, which violates the
sweep's intent.

### Gap 2: inventory mutation surface

Before this wave, the only inventory ports were:

  - `IPlayerInventorySnapshotRepository` — point-in-time capture for
    persistence.
  - `IItemDelivery` (items plugin) — single-item delivery wrapping
    `ItemSpec`.
  - `IInventoryShareGuard` (survival) — cross-mode item carry policy.

There was no port for "take N of this item", "clear hotbar", "swap two
slots", "set slot to nothing", "apply a loot table", or "issue a kit".
Every use case that needed these had to either (a) reach into a sibling
plugin's adapter (forbidden by ADR-0022), or (b) wait for Wave 6 to
bring Bukkit `PlayerInventory` calls inline (which would put platform
calls in domain layers).

The Round 4 wave `5pve-survival` shipped a survival inventory guard but
no generic mutator; the Round 5 plan calls for both before Wave 6 so
the Paper-side `PaperInventoryMutator` can land as a drop-in adapter
override rather than a fresh design.

### Companion concerns

- **Per-region event routing** — Wave 5n region permission service
  subscribes to events, but the bus is type-routed only. Without a
  spatial filter, every subscriber re-implements "did this event
  happen inside region X?" — wasteful and error-prone.
- **Per-slot type lock** — helmet slot rejecting non-armor is a Minecraft-
  native invariant that should live in domain, not be re-derived in
  every adapter.
- **Bulk inventory transfer event** — chest deposit, hopper output, kit
  issuance, loot delivery all bulk-shift items; an audit / replay
  subscriber wants one event per transfer, not N per item.

## Decision

Land a **world event + inventory mutation foundation** across
`:plugin:core` with these shapes. **No Paper adapter** (deferred to
Wave 6); **no DB migration** (event domain; persistence flows through
the existing audit-log writer).

### Core domain — world reason enums (`core.domain.world.*`)

Pure-Kotlin classification types backing the new events:

- **`TeleportReason`** — sealed: `Command`, `Respawn`, `Portal(portalId?)`,
  `Admin`, `MatchTransition(matchId)`, `EnderPearl(itemKind)`.
- **`BlockInteractAction`** — sealed: `RightClick`, `LeftClick`,
  `PhysicalContact`, `OffHandUse`. Sealed (not enum) so future variants
  can grow a payload without breaking exhaustive callers.
- **`ChunkUnloadReason`** — enum: `PLAYER_LEFT_RANGE`, `WORLD_UNLOAD`,
  `FORCED_BY_ADMIN`.
- **`TimeChangeReason`** — sealed: `NaturalCycle`, `Admin`, `GameRule`,
  `MatchPhase(matchId)`.

### Core domain — inventory vocabulary (`core.domain.inventory.*`, NEW)

Pure-Kotlin types backing the new ports:

- **`ClearScope`** — sealed: `All`, `Hotbar`, `MainInventory`, `Armor`,
  `OffHand`, `Slot(slotIndex)`. The `Slot` variant validates 0..40.
- **`InventoryError`** — sealed: `PlayerOffline`, `InventoryFull`,
  `InvalidSlotIndex`, `UnknownItem`, `SlotTypeLocked`, `InsufficientStock`.
  Covers every failure mode of `IInventoryMutator` without crossing the
  raw-throwable boundary (Konsist rule "application layers must not
  import raw JVM throwable types").
- **`SlotTypeLockRule`** — `fun interface (slotIndex, itemId) -> Boolean`
  with two impls: `PERMISSIVE` (allows everything) and
  `DefaultSlotTypeLockRule` (Minecraft armor-slot semantics: helmet slot
  accepts ids containing "helmet", chestplate slot accepts "chestplate",
  etc., hotbar / main / off-hand accept anything).
- **`InventoryTransferTarget`** — sealed: `PlayerInventory(playerId)`,
  `BlockContainer(world, position, containerKind)`,
  `VirtualContainer(key)`, `Dropped(world, position)`. The "other end"
  of an `InventoryTransferred` event.
- **`KitId`** — `JvmInline value class` namespaced lower-kebab-case id
  (`astera:pvp-starter`). Core ships the id type only; the Match-side
  `PreFillKit` registry resolves it.
- **`LootApplied`** — success payload of `IBulkInventoryOps.applyLootTable`,
  carrying delivered + leftover item maps.
- **`LootError`** — sealed: `UnknownLootTable`, `PlayerOffline`,
  `DeliveryFailed(cause)`.
- **`KitError`** — sealed: `UnknownKit`, `PlayerOffline`,
  `DeliveryFailed(cause)`.

### Core cross-plugin events (`core.domain.event.*`)

Per CLAUDE.md "sealed `DomainEvent` hierarchy lives in `plugin:core`",
all eight new events live here:

- **`PlayerMoved(playerId, from, to, world, atTick)`** — physical
  movement. Adapter (Wave 6) is expected to throttle (canonical: per
  moved chunk or 0.5-block delta) to avoid bus spam.
- **`PlayerTeleported(playerId, from, to, worldFrom, worldTo, reason, atTick)`**
  — discrete jump. Distinct world handles so cross-world subscribers
  can short-circuit when they only care about same-world moves.
- **`PlayerInteractedBlock(playerId, world, position, blockId, action, atTick)`**
  — single source of truth for skill-trigger-by-block, region permission
  interaction guard, custom block listener, pressure plate dispatch.
- **`ChunkLoaded(world, chunkX, chunkZ, atTick)`** /
  **`ChunkUnloaded(world, chunkX, chunkZ, reason, atTick)`** — spatial
  cache invalidation hooks.
- **`WeatherChanged(world, oldWeather, newWeather, atTick)`** — vendor-
  neutral `WeatherState` payload (reuses Wave 5n's existing
  `WeatherState` sealed family).
- **`TimeChanged(world, oldTime, newTime, reason, atTick)`** — `init`
  block validates Minecraft's `0..23999` daytime range. Adapter is
  expected to throttle natural-cycle publications (canonical: per
  1000-tick segment).
- **`InventoryTransferred(fromPlayer?, target, items, atTick)`** — one
  event per bulk transfer, not per item. `init` block validates non-
  empty items map + positive counts. `fromPlayer = null` means "system
  origin" (admin grant, environmental loot).

### Core outbound ports

Three new vendor-neutral interfaces:

- **`IInventoryMutator`** (`core.application.port.outbound.inventory.*`)
  — `give`, `take`, `clear`, `swap`, `setSlot`, `count`. Every fallible
  method returns `Result<T, InventoryError>` per the application-API
  rule. Slot indices use the canonical Minecraft layout (0..8 hotbar,
  9..35 main, 36..39 armor, 40 off-hand). `take` is **lenient** —
  returns actual count taken; callers that need strict semantics
  compose a `count` pre-check.
- **`IBulkInventoryOps`** — `applyLootTable(playerId, lootTableId)` ->
  `Result<LootApplied, LootError>`, `issueKit(playerId, kitId)` ->
  `Result<Unit, KitError>`. Both fire `InventoryTransferred` on
  success.
- **`IRegionEventFilter`** (`core.application.port.outbound.world.*`)
  — `subscribe(regionId, type, handler)`. Wraps `IDomainEventBus`
  subscription with a `(world, position)` extractor + region-membership
  predicate. Events without a spatial payload (e.g. `WeatherChanged`)
  are dropped at the filter — the bus still routes them to non-region
  subscribers normally.

### Core in-memory impls (`core.application.service.{inventory,world}.*`)

Phase 1 implementations, drop-in-overridable at priority 100 by the
Wave 6 Paper adapter:

- **`InMemoryInventoryMutator`** — fixed-size `Array<Slot?>` per player
  (41 slots). Give path: top-up existing same-id stacks → fill empty
  main+hotbar slots → return `InventoryFull` with leftover. No max-
  stack-size in Phase 1 (Paper adapter enforces per-material limits).
- **`InMemoryBulkInventoryOps`** — composes the mutator + the
  in-memory event bus + injected `lootTableResolver` / `kitResolver`
  lambdas. The resolvers are lambdas (not dedicated registry ports)
  to keep the port surface lean; Wave 5pve-content / Wave 5e wiring
  becomes a single-line lambda swap.
- **`InMemoryRegionEventFilter`** — wraps `IDomainEventBus.subscribe`
  with a position-extractor + region-membership decorator. Default
  membership predicate returns `false` (so the filter is inert until
  Wave 5n wires `IStageRegionIndex`); the canonical extractor covers
  all five spatial events.

### Konsist guards (+2 rules, total 50)

- **`inventory domain stays pure (ADR-0111)`** — `core.domain.inventory.*`
  must not import Bukkit family, kotlinx.serialization, JDBC, Exposed,
  postgres, lettuce, or Koin.
- **`inventory and world ports stay vendor-neutral (ADR-0111)`** —
  `core.application.port.outbound.inventory.*` and
  `core.application.port.outbound.world.*` must not import Bukkit
  family or the same persistence / serialization set.

### Wiring (`AsteraCorePlugin.onEnable`)

Three new `services.register<...>(impl, priority = 0)` lines, placed
after the Wave 5pve-survival block and before the Wave 5p
advancement/dialog/tutorial block. Loot table + kit resolvers are
seeded with `{ _ -> null }` lambdas — Phase 1 returns `UnknownLootTable`
/ `UnknownKit` until the registries land.

## Consequences

### Positive

- **Wave 6 Paper sweep can stay "wrap-only"** — the domain events the
  Paper-side `BukkitEventAdapter` will publish are already declared.
  The adapter writes `org.bukkit.event.player.PlayerMoveEvent ->
  PlayerMoved(...)` glue without growing the domain.
- **Region permission service unblocked** — Wave 5n can finish its
  per-region routing by injecting `IRegionEventFilter` and subscribing
  to `PlayerMoved` / `PlayerInteractedBlock` per region, rather than
  re-implementing membership in every listener.
- **Audit + replay capture single-event-per-bulk** — `InventoryTransferred`
  cuts the event volume by ~10x for chest fills / kit issuance vs.
  per-item `ItemAcquired` emission.
- **`take` lenient semantics matches every existing caller** — the
  Wave 5e respawn pipeline, the Wave 5pve gathering yield service,
  and the Wave 5refine reroll cost flow all already do "take up to N"
  in their use cases. Strict-N callers compose with `count`.

### Negative / Trade-offs

- **No max-stack-size in Phase 1 mutator** — the in-memory impl lets a
  single slot hold 99999 apples. Use cases that need Minecraft-style
  enforcement (no more than 64 per slot for non-tools) must either
  defer to the Wave 6 Paper adapter or build the check on top. Mitigated
  by the test contract being explicit about the absence.
- **No per-event throttling enforcement** — `PlayerMoved` /
  `TimeChanged` adapters must self-throttle. If a buggy adapter
  publishes per server tick, the in-memory bus will dispatch all of
  them and burn CPU. Mitigated by the adapter being Wave 6 (still in
  design) — we can land throttling as part of the adapter contract,
  not the event type.
- **Two registry lambdas in `InMemoryBulkInventoryOps`** — leaks the
  fact that no `ILootTableRegistry` / `IKitRegistry` port exists yet.
  Mitigated by the lambdas being trivially swappable; the day Wave
  5pve-content / Wave 5e lands a registry port, the wiring change is
  one line.

### Mitigations

- **Konsist guards prevent domain decay** — the two new rules above
  catch the most likely future regression (a Paper-side adapter writer
  importing `org.bukkit.event.player.PlayerInteractEvent` into the
  event class to "save a conversion").
- **Slot-type lock is pluggable** — `SlotTypeLockRule` is a `fun
  interface` so Wave 5refine / Wave _items-1 can override per-item
  with item-registry-aware logic without touching the default.
- **`InventoryTransferTarget.Dropped` covers the "no holder" case** —
  inventory drops (player death, full inventory overflow) still get
  represented in the audit log, no special-case event needed.

## Alternatives Considered

- **Single combined "PlayerSpatialEvent" with a discriminator field** —
  rejected: forces every subscriber to switch on the discriminator,
  defeating type-based bus routing.
- **`IInventoryMutator` returning `kotlin.Result<T>`** — rejected:
  the codebase uses Astera's `Result<T, E>` (sealed `Success` /
  `Failure`) precisely because `kotlin.Result` is throwable-based and
  leaks platform exceptions into domain code (per the type's own kdoc).
- **Dedicated `ILootTableRegistry` / `IKitRegistry` ports in this wave**
  — rejected: would expand the wave's territory into Wave 5pve-content
  / Wave 5e turf. Lambdas are a 1-line refactor target.
- **`IRegionEventFilter` as a subclass of `IDomainEventBus`** — rejected:
  the two have different invariants (region-filtered vs. type-filtered);
  conflating them would force every bus subscriber to deal with a
  region argument it doesn't care about.
- **Putting world event reasons on `WeatherState` / `BlockType`
  directly** — rejected: the reason is per-event, not per-state. Wedging
  it into the state would force every existing `WeatherState.Rain`
  caller to know why the rain started.

## References

- Wave handoff: `/home/node/.claude/plans/wave-5g-core-hardening-giggly-sunset.md`
  §"Round 5 + Round 6" → §"Wave 5world-events (ADR-0111)"
- Konsist rules: `tools/architecture-test/.../ArchitectureTest.kt`
  (`inventory domain stays pure (ADR-0111)`,
  `inventory and world ports stay vendor-neutral (ADR-0111)`)
- Companion ADRs: ADR-0022 (sibling isolation), ADR-0080 (Wave 5n
  world environment + spatial index — `IRegionEventFilter` will
  delegate region membership to that work's `IStageRegionIndex`),
  ADR-0092 (Wave 5n `IRegionPermissionService` — Phase 1 subscriber
  for `PlayerInteractedBlock`), ADR-0107 (Wave 5pve-survival —
  `IInventoryShareGuard` complements `IInventoryMutator` with
  cross-mode carry policy).
- Wave 6 sweep position: `Plan v5` "Wrap" / "Skip" table —
  `Player teleport/spawn`, `Inventory ops`, `Block place/break` are
  marked **Wrap** ("use Bukkit, don't reinvent"); this wave creates
  the domain shape that wrap will translate into.
