# Ports and Adapters

Astera's hexagonal seam — what the application asks the outside world for, and
who implements those asks.

## Outbound ports (`plugin/application/port/outbound/`)

Application defines; adapters implement. Phrased in domain types only.

| Port | Phase 1 impl | Future impl |
|---|---|---|
| [`IPlayerGateway`](../../plugin/application/src/main/kotlin/com/astarworks/astera/application/port/outbound/IPlayerGateway.kt) — find player by name, send message, give weapon | `McPlayerGateway` (`adapter-minecraft-api/binding/`) wired to `PaperServer` | unchanged |
| [`IWeaponRegistry`](../../plugin/application/src/main/kotlin/com/astarworks/astera/application/port/outbound/IWeaponRegistry.kt) — read-only weapon lookup | `MutableWeaponRegistry` (in-memory, application-side) | DB-backed (Phase 4, UGC marketplace) |
| [`IBroadcaster`](../../plugin/application/src/main/kotlin/com/astarworks/astera/application/port/outbound/IBroadcaster.kt) — publish DomainEvent | `PaperBroadcaster` (logs the event) | fan-out to Redis pub/sub + persistence + Discord (Phase 2+ ) |
| [`IMessageRenderer`](../../plugin/application/src/main/kotlin/com/astarworks/astera/application/port/outbound/IMessageRenderer.kt) — resolve `MessageKey` + placeholders → string | `SimpleMessageRenderer` (`application/i18n/`, no platform dep) | unchanged; LLM real-time translator can wrap (Phase 6) |
| [`IScheduler`](../../plugin/application/src/main/kotlin/com/astarworks/astera/application/port/outbound/IScheduler.kt) — currentTick + runLater + runRepeating + runAsync | `PaperScheduler` (`adapter-minecraft-impl-paper`) | `FoliaScheduler` (region-based, Phase 2 mid+) |
| [`ICooldownTracker`](../../plugin/application/src/main/kotlin/com/astarworks/astera/application/port/outbound/ICooldownTracker.kt) — per-(player, weapon) cooldown | `InMemoryCooldownTracker` (`application/service/`) | per-skill granularity (Phase 2 mid) |
| [`IWorldGateway`](../../plugin/application/src/main/kotlin/com/astarworks/astera/application/port/outbound/IWorldGateway.kt) — place/remove block, particle, sound | *not yet implemented* | `PaperWorldGateway` (Phase 2 mid) |
| [`Repository<ID, T>`](../../plugin/application/src/main/kotlin/com/astarworks/astera/application/port/outbound/Repository.kt) — generic CRUD | *not yet implemented* | `adapter-persistence-postgres` Exposed-backed (Phase 2 mid) |
| [`IPlayerRepository`](../../plugin/application/src/main/kotlin/com/astarworks/astera/application/port/outbound/IPlayerRepository.kt) — Repository<PlayerId, PlayerProfile> + findByDisplayName | *not yet implemented* | Postgres (Phase 2 mid) |
| [`IMatchRepository`](../../plugin/application/src/main/kotlin/com/astarworks/astera/application/port/outbound/IMatchRepository.kt) — find active / by phase / containing player | *not yet implemented* | Postgres (Phase 3) |
| [`IStageRepository`](../../plugin/application/src/main/kotlin/com/astarworks/astera/application/port/outbound/IStageRepository.kt) — Repository<StageId, Stage> | *not yet implemented* | Postgres + schematic file loader (Phase 3) |
| [`ICurrencyLedger`](../../plugin/application/src/main/kotlin/com/astarworks/astera/application/port/outbound/ICurrencyLedger.kt) — balance / wallet / debit (Result) / credit | *not yet implemented* | Postgres (Phase 4) |

## Test fakes (`plugin/test-fixtures/`)

Vendor-neutral; no Minecraft imports (Konsist-enforced).

| Fake | Use |
|---|---|
| `FakePlayerGateway` | records sendMessage / giveWeapon; register name → id |
| `FakeBroadcaster` | records published events |
| `FakeWeaponRegistry` | in-memory; register(spec) |
| `FakeMessageRenderer` | echoes key + placeholders; records call list |
| `FakeScheduler` | controllable virtual clock; advanceTicks(n) |
| `FakeWorldGateway` | records placements / removals / particle + sound |
| `FakeCurrencyLedger` | in-memory wallet ops; mirrors Wallet semantics |
| `InMemoryRepository<ID, T>` | generic CRUD; caller supplies `idOf` extractor |
| `weaponSpec(...)` builder | sensible defaults matching `example-sword.yaml` |

## Vendor-neutral Minecraft abstraction (`adapter-minecraft-api/`)

This is *not* an application port — it's the abstract layer that lets us
swap server cores (Paper / Folia / Spigot / Velocity / Fabric). See
[mc-adapter-layer.md](mc-adapter-layer.md).

| Type | Phase 1 impl |
|---|---|
| `IMcServer` (find player by id / name) | `PaperServer` |
| `IMcPlayer` (name, location, locale, sendMessage, giveMaterial, heldItemMaterialKey) | `PaperPlayer` |
| `IMcEvent` (sealed: PlayerLeftClickAir / PlayerLeftClickBlock) | `BukkitEventAdapter` translates Bukkit `PlayerInteractEvent` |

## Inbound ports

Phase 1 doesn't formalize an inbound-port interface — Bukkit's command handler
calls `GiveWeaponUseCase.execute(...)` directly. Phase 2 mid will introduce
`ICommandHandler` as inbound port when the command tree grows past `/astera`.

## Adapter modules summary

| Module | What it implements |
|---|---|
| `adapter-minecraft-api` | vendor-neutral `IMc*` + port bindings (`McPlayerGateway`) |
| `adapter-minecraft-impl-paper` | Paper concrete `PaperServer` / `PaperPlayer` / `PaperScheduler` / `PaperBroadcaster` / `BukkitEventAdapter` |
| `adapter-persistence-postgres` | (Phase 2 mid) Exposed-backed Repository impls |
| `adapter-messaging-redis` | (Phase 2 mid+) `IBroadcaster` extension via pub/sub, future `ICache` |
| `adapter-providers/{weaponmechanics,oraxen,mythicmobs}` | soft-dep optional integrations (ADR-0009) |

## Adding a new port

1. Add interface in `plugin/application/port/outbound/` phrased in domain types.
2. Add a fake in `plugin/test-fixtures/`.
3. Use it from a use case; cover with unit tests.
4. Provide an implementation in the appropriate adapter module.
5. Wire it in `platform-paper-plugin/AsteraPlugin.kt` (or future `platform-*`).

If the port's contract is non-obvious, add an ADR.
