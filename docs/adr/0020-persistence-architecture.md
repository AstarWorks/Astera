---
status: Draft
date: 2026-05-17
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0020: Persistence Architecture — Exposed + HikariCP + Flyway

## Status

Draft

## Context

Through Wave 1 / Wave 2 (commits up to `1cdde97`), Astera's five primary
repositories live in-process only:

- `IPlayerStatsRepository` (`InMemoryPlayerStatsRepository`)
- `IMatchRepository` + `IStageRepository` (in-memory)
- `ICurrencyLedger` (`InMemoryCurrencyLedger`)
- `IPermissionService` (`InMemoryPermissionService`)

A server restart wipes every wallet, player stat, role assignment, and match
record. For Phase 1 dev that was fine; Phase 3 (Siege Warfare) and Phase 4
(UGC / Web admin) both need durable state. ADR-0005 already chose Postgres
over Mongo; ADR-0014 codified the JSONB-vs-columnar rule; ADR-0022 fixed the
multi-plugin runtime layout. This ADR fills in the *how* — how Astera connects
to Postgres, runs migrations, and overlays in-memory defaults — without
breaking the "Astera must boot in vanilla Paper" invariant.

Key non-negotiables coming into this design:

1. CLAUDE.md rule #5: versions live in `gradle/libs.versions.toml`, never in
   module `build.gradle.kts` files.
2. Astera is **not** Spring. AstarManagement's `exposed-spring-boot4-starter`
   pattern doesn't apply — we need a barebones `HikariDataSource` →
   `Database.connect()` bootstrap.
3. Konsist 26+ rules forbid `org.bukkit.*` in `domain` / application; persistence
   adapters live in their own plugin module which is allowed to touch JDBC but
   still must not pull Bukkit.
4. Postgres is *optional*. Missing env vars must not break the plugin boot.

## Decision

### Stack

- **Exposed 1.3.0** DSL (no DAO). Pinned in `libs.versions.toml`. DSL keeps
  N+1 queries visible at call sites; DAO obscures them.
- **HikariCP 6.2.1** (pinned). Manually constructed in `PersistenceModule.kt` —
  no Spring auto-config.
- **Flyway 12.6.1** for schema migrations (`V{N}__name.sql` under
  `src/main/resources/db/migration/`). Flyway runs once on `onEnable` after
  the DataSource is built and before any repository registers.
- **PostgreSQL JDBC driver 42.7.11**. Postgres 17 is the dev target.
- **Testcontainers 1.21.4** for integration tests. JUnit 5 + AssertJ.
- **kotlinx-serialization 1.9.0** for JSONB serialization. A small
  `JsonbColumnType<T>` mirrors AstarManagement's pattern but drops the Spring
  + `@Contextual` baggage Astera doesn't need.

### Layering

```
plugin/persistence/
├─ AsteraPersistencePlugin.kt   ← env-var bootstrap + register Postgres overlays
├─ PersistenceModule.kt         ← PostgresConfig.fromEnv() + connectAndMigrate()
├─ jsonb/
│  ├─ JsonbColumnType.kt        ← Exposed ColumnType<T> over kotlinx.serialization
│  ├─ MatchJson.kt              ← DTO copies of Match/Team/Score for JSONB
│  ├─ StageJson.kt              ← Region DTO
│  └─ PermissionsJson.kt        ← Set<Permission> wrapper for JSONB
├─ table/
│  ├─ PlayerStatsTable.kt
│  ├─ MatchesTable.kt
│  ├─ StagesTable.kt
│  ├─ WalletsTable.kt
│  ├─ RoleDefinitionsTable.kt
│  └─ RoleAssignmentsTable.kt
└─ repository/
   ├─ PostgresPlayerStatsRepository.kt
   ├─ PostgresMatchRepository.kt
   ├─ PostgresStageRepository.kt
   ├─ PostgresCurrencyLedger.kt
   └─ PostgresPermissionService.kt
```

### Overlay pattern (priority + loadbefore)

`ServiceRegistry.register(impl, priority = N)` resolves the highest-priority
entry on `get<T>()`. Persistence plugin registers each impl at **priority 100**;
feature plugins register their in-memory defaults at priority 0.

For this to actually load-bear, persistence's `plugin.yml` uses
`loadbefore: [Astera-Weapons, Astera-Combat, Astera-Match, Astera-Stats, Astera-Economy]`
so persistence's `onEnable` runs *before* each feature plugin's `onEnable`.
Each feature plugin then queries `services.get<X>()` first:

```kotlin
val matches: IMatchRepository =
    services.get<IMatchRepository>()
        ?: InMemoryMatchRepository().also {
            registrations += services.register<IMatchRepository>(it, priority = 0)
        }
```

When env vars are set the lookup wins with Postgres (priority 100); otherwise
it falls back to in-memory and registers itself. `./gradlew check` runs
without Docker because integration tests gate on
`Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable, …)`.

### Cross-plugin KClass identity (the part that nearly bit us)

For a `priority = 100` overlay to actually win the `services.get<T>()` lookup
across plugin boundaries, the `KClass<T>` used at register time and the one
used at get time **must be the same JVM `Class`**. Paper's `PluginClassLoader`
checks the *plugin's own jar first*, then falls through `depend`/`softdepend`
chains — so if every feature plugin's `shadowJar` bundles a copy of plugin/core
(via plain `implementation`), each plugin loads its own copy of every
`IPlayerStatsRepository`, every `IDomainEventBus`, etc. — different `Class`
objects, different `KClass`, lookup misses.

Two coordinated changes prevent that:

1. **The four feature-side ports + their dependent domain types moved to
   `plugin/core`**: `IPlayerStatsRepository`, `IMatchRepository`,
   `IStageRepository`, `ICurrencyLedger`, plus `PlayerStats`, `Match` /
   `MatchId` / `MatchPhase` / `Score` / `Team` / `TeamId`, `Stage` / `StageId` /
   `Region` / `Plot` / `PlotId`, `Currency` / `Wallet` / `WalletError`.
   `IPermissionService` was already in core.

2. **Feature plugin `shadowJar`s now `exclude(project(":plugin:core"))`**:
   astera-weapons, astera-combat, astera-match, astera-stats, astera-economy
   no longer bundle plugin/core's bytes. At runtime the depend chain to
   Astera-Core resolves the type from a single classloader, so every plugin
   sees the same `Class<IPlayerStatsRepository>`.

`plugin/persistence/build.gradle.kts` uses `compileOnly(project(":plugin:core"))`
for the same reason. Tests still use `testImplementation(project(":plugin:core"))`
so the Gradle test JVM (single classloader) sees the types directly.

This fix also retroactively cleans up Wave 1's cross-plugin event subscription
wiring: in-process listeners (`MissionCompleted`, `MatchEnded`, etc.) now
resolve to a single canonical `Class` across plugins.

### Atomic Transfer

`ICurrencyLedger` gains:

```kotlin
public fun transfer(
    from: PlayerId, to: PlayerId, currency: Currency, amount: Long,
): Result<TransferResult, WalletError>
```

`PostgresCurrencyLedger.transfer` wraps debit + credit in a single
Exposed `transaction { … }` block under `READ_COMMITTED` isolation. The debit
is a conditional `UPDATE … WHERE balance >= amount` so the lost-update race
surfaces as `InsufficientFunds` instead of corrupting state. If
read-your-writes across concurrent transfers between the same two accounts
ever matters, upgrade to `SERIALIZABLE` or `SELECT … FOR UPDATE` —
Phase 1 / Phase 3 don't need it.

In-memory `transfer` keeps the existing `ConcurrentHashMap.compute` debit
followed by a credit; the credit path of in-memory cannot fail so the
compensating refund is structurally present but never exercised.
`TransferUseCase` delegates to `ledger.transfer` and now only does event
publishing on success.

### Env vars

| Variable | Required | Example |
|---|---|---|
| `ASTERA_PG_URL` | yes | `jdbc:postgresql://localhost:5432/astera` |
| `ASTERA_PG_USER` | yes | `astera` |
| `ASTERA_PG_PASSWORD` | yes | (secret) |

Missing any of the three → `AsteraPersistencePlugin.onEnable` logs a warning
and returns. Each feature plugin then constructs its in-memory impl. No
hidden default URL / user — fail loudly, in-memory only.

### JSONB columns

Per ADR-0014: aggregate value objects that always travel together belong in
JSONB. Concrete picks:

- `matches.teams` (`List<Team>`, contains `Set<PlayerId>` per team)
- `matches.score` (`Map<TeamId, Int>`)
- `stages.regions` (`List<Region>`)
- `role_definitions.permissions` (`Set<Permission>`)

Everything else (kill/death counts, wallet balances, match phase, role
assignments) is columnar so it stays cheap to query / index.

Domain types aren't `@Serializable`. Instead each JSONB column has a
hand-written `*Json` DTO in `plugin/persistence/jsonb/` with `fromDomain` /
`toDomain` conversions. That keeps `plugin/core/domain` annotation-free per
the existing "domain is pure" rule and prevents accidental wire-format
coupling (renaming a domain field doesn't silently corrupt stored JSON).

### Testcontainers IT gate

`PostgresIntegrationTestBase`'s `@BeforeAll` calls
`Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable, …)`.
Docker-less environments report the IT as skipped; `./gradlew check` stays
green. With Docker, the harness starts `postgres:17-alpine`, runs Flyway, and
exposes a `Database`. Wave 3 ships one IT (`PostgresPlayerStatsRepositoryIT`)
covering the round-trip + `all()`; later waves add more as they touch each
repo.

## Consequences

### Positive

- Postgres becomes the system of record when env vars are set — server
  restarts no longer wipe player progression.
- Adding a sixth repository (e.g. `IShopService` in Phase 4) follows the same
  port-in-core + Postgres-in-persistence template.
- The cross-plugin classloader / overlay design is now explicit (instead of
  the latent bug it was in Wave 1).
- `transaction { … }` boundary is a single block per use case — no Spring
  transaction manager, no proxy, no surprises.

### Negative / trade-offs

- The shadowJar `exclude(project(":plugin:core"))` change in every feature
  plugin is load-bearing for the priority overlay AND for cross-plugin event
  dispatch. Anyone forgetting it on a new plugin gets a runtime "lookup
  returns null" failure that won't surface in unit tests (single classloader).
  Phase C of Wave 3 documents the pattern; future plugin scaffolding should
  bake it into the `astera.kotlin-paper` convention plugin.
- Persistence module compile-depends on plugin/core's port + domain types.
  Moving a port out of core requires also moving its dependent value types
  out, which can ripple. The current set is finalized; new ports for Phase 4
  (`IShopService`, `IEnderChestService`) need to land in core from day one.
- Domain ↔ JSON DTOs are hand-maintained. A new field on `Match` requires
  adding it to `MatchTeamsJson` / `MatchScoreJson` too, or the column
  silently drops the value. Mitigation: a Konsist or unit test that does a
  property-based round-trip per JSONB type when each domain class changes.
- AstarManagement uses `@Contextual` UUID/Instant/BigDecimal serializers via
  a shared `SerializersModule`. Astera's `JsonbColumnType.DefaultJson` skips
  these because the current domain doesn't need them. Phase 4 may add Java
  time / BigDecimal usage on JSONB columns — register contextual serializers
  then.

### Operational

- Migration ordering: V1 `player_stats` (α), V2 `matches` + `stages` (β),
  V3 `wallets` (γ), V4 `role_definitions` + `role_assignments` (δ). Numeric
  order matches Flyway's execution order.
- `DefaultRoles.builtin()` is re-seeded into `role_definitions` on every
  `onEnable` via `UPSERT` so updating the in-code permission set
  automatically rolls forward.
- `MatchId` / `StageId` / `TeamId` are kebab-case `String` (mirrors the
  existing domain) — table PKs are `TEXT`, not `UUID`. Only `player_id` is
  UUID and we use Exposed's `javaUUID(name)` (returns `Column<java.util.UUID>`)
  to avoid the 1.3.0 default `Column<kotlin.uuid.Uuid>` which would force
  `@OptIn(ExperimentalUuidApi::class)` across all repos.

## Alternatives considered

- **JOOQ**: codegen pipeline adds build-time complexity; Exposed DSL is
  closer in spirit to the rest of the Kotlin codebase.
- **Spring Data JDBC**: assumes Spring Boot; would require pulling Spring
  into every Paper plugin module.
- **Raw JDBC**: too much boilerplate per repository; loses Exposed's
  composable predicates.
- **DataDriven / MinecraftExposed** (ADR-0017): targets the *content*
  authoring layer (YAML → in-memory `Contents<T>`), not the durable-state
  persistence layer. Different problem.

## Relationship to other ADRs

- ADR-0005 — Postgres choice (motivation).
- ADR-0014 — JSONB vs columnar split (applied here).
- ADR-0022 — multi-plugin runtime model that this overlay extends.
- ADR-0025 — event wiring (relies on the same single-classloader fix).

## Notes for Wave 4+ (Declarative GUI / Paper runtime / Phase 4 Web)

- The `IWorldDirManager` port landed in `plugin/core` with a vacant impl —
  Wave 5's Paper runtime fills it in with `Bukkit.getWorldContainer()`.
- Wave 4 / 5's new ports go directly in `plugin/core/.../application/port/outbound/`
  to keep KClass identity working out of the box.
- Phase 4's web admin tier connects to the same Postgres via its own
  connection pool — schema is shared, the JVM is not.
