---
status: Accepted
date: 2026-06-02
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0156: Paper world + block + barrier adapters (Wave 6b-1)

## Status

Accepted. First sub-wave of Wave 6b (Phase 1 PoC closing). Ships three
Paper adapters across two plugins; companion sub-waves
[[adr/0157-paper-hud-adventure-ui]] (Wave 6b-2, HUD / Adventure UI) and
[[adr/0158-paper-inventory-and-bridges]] (Wave 6b-3, Inventory +
Broadcaster + RewardBridge) follow in subsequent commits.

## Context

After Wave 6a Combat polish (ADR-0155) shipped the projectile / status
/ cooldown stack, the three remaining Phase 1 PoC smoke-test
prerequisites (per `IMPLEMENTATION-ROADMAP.md` §"Phase 1 PoC end-to-end
完成: Wave 6b 終了時点") that need world-level Bukkit access are:

- `IWorldGateway` — placeBlock / removeBlock / spawnParticle /
  playSound / teleport. Currently `NoopWorldGatewayForEffects` at
  priority 0 (DEBUG-logged no-op).
- `IBlockStateGateway` — durability HP tracking for custom blocks.
  Currently `InMemoryBlockStateGateway` at priority 0 (in-memory map
  with no world mutation).
- `IBarrierGateway` (match plugin) — match-scoped invisible /
  semi-visible barriers. Currently `InMemoryBarrierGateway` at
  priority 0 (state-only, no visual).

This wave ships Paper concretes for all three at priority 100. They
unlock smoke-test items #5 (spawn teleport), #6 (projectile trail),
#7 (potion icon — partially; the icon mirror lands via
`PaperStatusEffectContainer` from ADR-0155 but spawn-particle visuals
on hit need `PaperWorldGateway.spawnParticle`).

## Decision

### §A — `PaperWorldGateway` (core)

`plugin/core/.../adapter/paper/world/PaperWorldGateway.kt`, 240 LOC,
8 `IWorldGateway` overrides.

Constructor: `(server, scheduler, eventBus)`. All 8 methods Bukkit-
backed:

| Method | Bukkit API |
|---|---|
| `placeBlock` / `removeBlock` / `blockAt` | `World.getBlockAt(x, y, z)` + `Material.matchMaterial` |
| `spawnParticle` | `World.spawnParticle(Particle, Location, count, offX, offY, offZ)` via `Particle.valueOf(identifier.uppercase())` |
| `playSound` | `World.playSound(Location, Sound, volume, pitch)` via Paper 1.21 `Registry.SOUNDS.get(NamespacedKey)` (Sound is now Registry-backed, not enum) |
| `teleport` | `Player.teleport(Location)` |
| `lobbySpawn` | `server.worlds.first().spawnLocation` + `Player.teleport` |
| `spectatorSpawn` | **Phase 1 stub** — falls back to `lobbySpawn` with DEBUG log. Real impl needs an `IMatchSpawnRegistry` port that `:plugin:match` populates from `content/stages/<id>.yaml` arena metadata. Behaviour shifts from prior "always-fail UnknownMatch" to "land at lobby" — strictly better. |

Helper privates: `resolveWorld`, `toLocation`, `onlinePlayer`,
`resolveParticle`, `resolveSound`. Unknown-world cases on
Unit-returning methods log-and-drop (matching the NoOp's behaviour);
on Result-returning methods, map to `WorldGatewayError.Storage("world
not loaded")` since the sealed surface lacks a dedicated
`WorldNotFound` / `BackendUnavailable` variant.

### §B — `PaperBlockStateGateway` (core)

`plugin/core/.../adapter/paper/world/PaperBlockStateGateway.kt`,
433 LOC, 7 `IBlockStateGateway` overrides.

Constructor: `(server, scheduler, eventBus, customBlockRegistry,
durabilityRule, random?)`.

| Method | Bukkit API |
|---|---|
| `getDurability` | Read-only against internal `tracker: ConcurrentHashMap<BlockKey, BlockState>` (no Bukkit call — generic blocks have no HP API) |
| `applyDamage` | Tracker mutation + `IScheduler.currentTick()` for `lastDamagedTick`. No world swap (Wave-5d raw-damage path; upstream owns "did it break") |
| `applyDamageFromSkill` | `Server.getWorld(name).getBlockAt(x,y,z).type` read (default-state seed via `Material.key`) → `Block.type = Material.AIR` on break + `IDomainEventBus.publish(BlockDamagedBySkill / BlockBrokenBySkill)` |
| `restore` | Tracker mutation only (Bukkit block was never replaced — restoring HP doesn't need a world write) |
| `track` | Tracker mutation only (placement event upstream owns the world swap) |
| `placeCustomBlock` | `World.getBlockAt(x,y,z).type = Material.matchMaterial(spec.baseBukkitMaterial)` + tracker mutation + `CustomBlockPlaced` event |
| `tracked` | In-memory scan of tracker, filtered by `WorldId`, re-projected through `Vec3(x,y,z)` from integer `BlockKey` |

**Phase 1 stub: in-process `ConcurrentHashMap` durability store.**
Bukkit's generic `Block` API exposes no `PersistentDataContainer`;
PDC only attaches to `TileState` subclasses (sign / chest / banner /
skull). A `minecraft:stone` block has no tile entity. Phase 1 PoC
accepts process-local HP that wipes on `/restart`. Wave 6b-2 / 6c
deps: route the tracker through Postgres-backed
`IBlockChangeJournalRepository` so HP survives restarts.

`BlockKey` normalises fractional `Vec3` to integer cell via
`floor(pos.x).toInt()` (matches Bukkit's integer-cell world model).

No defensive re-dispatch to main thread per Folia regional contract
(ADR-0144 / 0145 / 0146 / 0151 precedent): callers MUST already be on
main; defensive round-trips would mask threading bugs.

### §C — `PaperBarrierGateway` (match)

`plugin/match/.../adapter/paper/PaperBarrierGateway.kt`, 247 LOC,
4 `IBarrierGateway` overrides (`spawn` / `despawn` / `updateStatus` /
`all`).

Constructor: `(server, scheduler, eventBus)`. Backed by Bukkit
`BlockDisplay` (Paper 1.20+ requirement) with `Material.BARRIER`
blockData wrapped in scaled `Transformation` so one mesh covers the
barrier's region rather than spawning N entities per filled position.

Internal: `ConcurrentHashMap<BarrierId, BarrierTracking>` mapping to
the Bukkit entity UUID for `despawn` lookup.

**Phase 1 limits documented in KDoc:**

1. **Visual-only.** `Material.BARRIER` via `BlockDisplay` projects a
   render entity, not a placed block, so no server-side collision.
   Combat enforces damage-absorption via `Barrier.region` (the visual
   is purely informational). Wave 6b-fix or WorldGuard-style region
   adapter ships server-side collision.
2. **No multi-world support.** `Barrier` domain has no `worldId`
   field; gateway resolves `server.worlds.firstOrNull()`. Adding
   `Barrier.worldId: WorldId` is a small follow-up (Phase 1 closing
   concern).
3. **Single `BlockDisplay` per barrier.** Cheaper rendering at the
   cost of coarser visual fidelity (compared to per-block spawn).

### §D — Wiring

`AsteraCorePlugin.onEnable` (after the existing priority-0 InMemory
registrations for `IBlockStateGateway` + `ISchematicLoader`):

```kotlin
services.register<IWorldGateway>(
    PaperWorldGateway(server, scheduler, eventBus),
    priority = 100,
)
services.register<IBlockStateGateway>(
    PaperBlockStateGateway(server, scheduler, eventBus,
                          customBlockRegistry = customBlockContents,
                          durabilityRule = durabilityRule),
    priority = 100,
)
```

`AsteraMatchPlugin.onEnable` (after the existing `barrierGateway`
resolution that lazily registers `InMemoryBarrierGateway` at
priority 0):

```kotlin
registrations += services.register<IBarrierGateway>(
    PaperBarrierGateway(server, scheduler, eventBus),
    priority = 100,
)
val barrierGateway: IBarrierGateway = services.getOrThrow<IBarrierGateway>()
```

The priority 100 overlay wins automatically; downstream callers don't
care which impl resolves.

No `onDisable` cleanup required: barrier display entities are
plugin-tracked but Bukkit removes them on world unload; world / block
gateways own no scheduled tasks beyond the existing scheduler's
lifecycle.

### §E — Out-of-scope (other Wave 6b sub-waves)

- `ISchematicLoader` Paper impl (FAWE-backed) — deferred to Wave 6b-1b
  or Wave 6c per ADR-0072 `IStructurePaste` deferral. The
  `InMemorySchematicLoader` stub continues to suffice for Phase 1
  PoC since arena loading uses an alternate path.
- `IWorldTimeGateway` / `IWorldWeatherGateway` / `IWorldRulesGateway`
  Paper impls — covered by the broader `World.setTime` / `setStorm` /
  `setGameRule` Bukkit surface. Not Phase 1 PoC smoke-test critical
  (the arena timer is independent of vanilla world time); deferred
  to Wave 6b-N or post-PoC.
- HUD / Adventure UI / Inventory / Bridges — Wave 6b-2 + Wave 6b-3
  scope.

## Consequences

### Positive

- Phase 1 PoC smoke-test items #5 / #6 / #7 (partial) become
  end-to-end testable: spawn teleport routes through real
  `Player.teleport`; projectile trail particles fire through
  `World.spawnParticle`; barrier visuals show as `BlockDisplay`
  entities; block break / repair events flow through `applyDamage` →
  `applyDamageFromSkill` → world swap.
- ADR-0149 §C invariant preserved: none of the three adapters touch
  `player.health`, `player.damage()`, or vanilla death paths. World /
  block / barrier visuals are mirrors of Astera-internal state,
  never inputs to player HP.
- `BlockKey` integer coord normalisation prevents the same Vec3
  block being tracked under two keys (floating-point drift bug
  prophylaxis).

### Neutral / costs

- `PaperBlockStateGateway` in-memory durability store wipes on
  `/restart`. Acceptable for Phase 1 PoC; Wave 6b-2 / 6c routes it
  through Postgres `IBlockChangeJournalRepository`.
- `PaperBarrierGateway` single-world limit (`Barrier.worldId` field
  missing). Doesn't block PoC because the smoke test uses one arena
  in `server.worlds.first()`. Follow-up: add `worldId` to the
  `Barrier` aggregate.
- `PaperWorldGateway.spectatorSpawn` Phase 1 stub falls back to
  `lobbySpawn`. Behaviour is strictly better than the previous
  NoopWorldGatewayForEffects's UnknownMatch fail, but real
  per-match-spawn requires the `IMatchSpawnRegistry` port that
  doesn't exist yet.

### Negative

- The `BarrierStatusChanged` domain event hinted at by the sub-C
  agent doesn't ship in this wave — the `PaperBarrierGateway`
  constructor reserves `scheduler + eventBus` parameters
  (`@Suppress("unused")`) so adding the event later is a port-shape-
  preserving change. Until then, HUD / analytics can't subscribe to
  barrier status transitions.

## Verification

- `./gradlew :plugin:core:compileKotlin` + `:plugin:match:compileKotlin`
  green.
- `./gradlew :tools:architecture-test:test` green (no new Konsist
  rules required; all three files live in already-permitted
  `adapter/paper/*` packages).
- `./gradlew check` BUILD SUCCESSFUL.

Manual integration check (Wave 6b smoke test prerequisites):
1. `/give @s minecraft:wooden_axe` then break an Astera custom block
   → `applyDamageFromSkill` fires + tracker HP decrements + world
   `Block.type = Material.AIR` once HP hits zero.
2. Cast a particle-emitting skill → `World.spawnParticle` invocation
   visible to nearby clients.
3. Create a match barrier → `BlockDisplay` entity visible to
   spectators in the arena world.

## References

- [[adr/0022-multi-plugin-architecture]] — sibling-isolation
  (PaperBarrierGateway in match plugin per matrix)
- [[adr/0026-paper-runtime-topology]] — Wave 6 Paper sweep umbrella
- [[adr/0072-wave-6-paper-impl-allow-list]] — IStructurePaste
  remains in allow-set per §E
- [[adr/0149-astera-internal-hp-death-model]] — §C invariant
  preserved across all three adapters
- [[adr/0151-paper-adapter-rewrite-astera-owns-damage]] — Folia
  regional contract precedent (no defensive main-thread re-dispatch)
- [[adr/0155-combat-polish-paper-sweep]] — Wave 6a-N2 precedent
- [[adr/0157-paper-hud-adventure-ui]] — companion sub-wave 6b-2
  (HUD / Adventure UI)
- [[adr/0158-paper-inventory-and-bridges]] — companion sub-wave 6b-3
  (Inventory + Bridges)
