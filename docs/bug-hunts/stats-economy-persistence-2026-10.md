# Stats / economy / persistence bug hunt — 2026-10

Evidence line numbers below refer to the pre-hunt revision (`7c952841^`) unless stated otherwise.

Local builds, tests, Docker and pushes are prohibited for this task. Regression tests are written before fixes; red/green execution is deferred to orchestrator-triggered GitHub Actions.

| Defect | Evidence | Fix commit | Regression |
| --- | --- | --- | --- |
| Deaths never recorded: RecordDeathUseCase constructed but no subscriber invokes it | `plugin/stats/src/main/kotlin/com/astarworks/astera/stats/platform/wiring/StatsWiring.kt:186`, `:203` | `fix(stats): record victim deaths on player kills` (7c952841) | RecordDeathUseCaseTest; ProgressionLoopScenarioTest player + environmental deaths |
| First stat write lost: cold find returns null, callers mutate detached PlayerStats, upsert only flushes the untouched cached container | `plugin/persistence/src/main/kotlin/com/astarworks/astera/persistence/repository/PostgresPlayerStatsRepository.kt:30`, `:37` | `dc6de4fa` | PostgresPlayerStatsRepositoryIT first mutation and detached upsert across fresh stores |
| Authored stock checked before owned-copy path and authoritative stock decrement; changing YAML remaining to zero blocks existing owners and ignores positive persisted stock | `plugin/economy/src/main/kotlin/com/astarworks/astera/economy/application/usecase/shop/ShopPurchaseUseCase.kt:126` (before fix) | `1d3fb6dc` | ShopOwnershipAndBrowseTest owned copy with authored zero stock |
| Shop YAML offer display_key parsed then ignored in GUI | `plugin/economy/src/main/kotlin/com/astarworks/astera/economy/application/config/ShopYamlConfig.kt:61`; `plugin/economy/src/main/kotlin/com/astarworks/astera/economy/application/usecase/shop/ShopBrowseUseCase.kt:287` | `13a5d508` | ShopOwnershipAndBrowseTest; ShopScenarioTest production shop intent |
| Fresh Postgres has no active season, so every PlayerRatingUpdated is dropped and leaderboard queries fail; in-memory mode seeds season-1 | `plugin/persistence/src/main/resources/db/migration/V48__competitive_seasons.sql:31`; `plugin/stats/src/main/kotlin/com/astarworks/astera/stats/application/eventbus/listeners/RecordRatingOnPlayerRatingUpdatedListener.kt:30` | `12ad8eea` | PostgresSeasonBootstrapIT migrated DB -> rating event -> leaderboard query |
| Escrow read/check/write lacks a cross-server lock: concurrent holds debit twice, concurrent settlements credit the same pot/stakes repeatedly | `plugin/persistence/src/main/kotlin/com/astarworks/astera/persistence/repository/PostgresBettingEscrowService.kt:47`, `:102`, `:133` (before fix) | `889189f9` | PostgresBettingEscrowConcurrencyIT holds, payouts and refunds with four blocked contenders |
| Empty Postgres stage overlay hides shipped YAML stages; old match FK also rejects content-only stages | `plugin/persistence/src/main/kotlin/com/astarworks/astera/persistence/platform/AsteraPersistencePlugin.kt:225` (before fix); `plugin/match/src/main/kotlin/com/astarworks/astera/match/platform/wiring/MatchWiring.kt:190`; V2 matches.stage_id FK | `cf6814c3` | PostgresContentStageMatchIT; existing game-sim match/spawn scenarios; ADR-0235 |
| Flyway info/migration or Database.connect failure leaks the Hikari pool because no PersistenceContext reaches the caller for cleanup | `plugin/persistence/src/main/kotlin/com/astarworks/astera/persistence/PersistenceModule.kt:90` (before fix) | `27c887e2` | PersistenceBootstrapTest failed connection closes pool without DB/Docker |
| Shop command error messages use {shop} in en/ja but receive shop-id, exposing an unresolved placeholder to players | `plugin/economy/src/main/kotlin/com/astarworks/astera/economy/command/ShopCommandSpec.kt:49`, `:56`; `content/languages/en.yaml:579` | `cf6ad125` | ShopOwnershipAndBrowseTest command executor; ShopScenarioTest invalid/missing shops in en and ja |
| Stats and economy discard reload registration handles; Stats also never removes its achievement GUI delegate, keeping disabled plugin services reachable | `plugin/stats/src/main/kotlin/com/astarworks/astera/stats/platform/wiring/StatsWiring.kt:182`, `:252`; `plugin/economy/src/main/kotlin/com/astarworks/astera/economy/platform/wiring/EconomyWiring.kt:426` (before fix) | `9da45517` | ProgressionShutdownScenarioTest production stop hooks preserve other plugins |


## Coverage and validation

Read all 350 production files present at the start: Stats 100, Economy 80,
Persistence 170 (including SQL migrations and plugin descriptors). Traced the
shared event bus, role gates (ADR-0179), repository registration timing
(ADR-0195), combat death publishers and Match stage consumers. Ten defects
received separate fix commits; no build, test, Docker, push or main-branch
operation was run. `git diff --check` found no whitespace errors.

Added 16 regression cases: five unit cases, seven Postgres integration cases,
and four game-sim scenarios. Tests were authored before their corresponding
production fixes. Red/green results are **unverified** until GitHub Actions runs;
Postgres ITs use the existing Docker-availability gate, so CI must have Docker
available to exercise them. Real Paper startup, migrations on existing databases,
and live lobby/game transfers remain unverified.

Design: preserve the existing Result APIs and content ownership, copy detached
stats into their canonical container, lock the parent betting-match row before
all escrow wallet movements, retain and unregister feature-owned handles, and
use new migrations only (V65/V66). Stage content ownership is recorded in
ADR-0235. No language keys were added or removed; the i18n fix supplies the
placeholder already present in both languages. Marketplace item fulfillment,
player betting UI and perk application are explicitly deferred in existing code;
this hunt did not implement those feature expansions.

## Changed files

- Stats: `platform/wiring/StatsWiring.kt`; new `RecordDeathUseCaseTest.kt`.
- Economy: `ShopBrowseUseCase.kt`, `ShopPurchaseUseCase.kt`, `ShopCommandSpec.kt`,
  `EconomyWiring.kt`; extended `ShopOwnershipAndBrowseTest.kt`.
- Persistence: `PersistenceModule.kt`, `AsteraPersistencePlugin.kt`,
  `PostgresBettingEscrowService.kt`, `PostgresPlayerStatsRepository.kt`,
  `MatchesTable.kt`; removed `PostgresStageRegistry.kt`; new V65/V66 migrations.
- Persistence regressions: `PersistenceBootstrapTest.kt`,
  `PostgresBettingEscrowConcurrencyIT.kt`, `PostgresContentStageMatchIT.kt`,
  `PostgresSeasonBootstrapIT.kt`; extended `PostgresPlayerStatsRepositoryIT.kt`.
- Game-sim: extended `ProgressionLoopScenarioTest.kt`, `ShopScenarioTest.kt`;
  new `ProgressionShutdownScenarioTest.kt`.
- Documentation: this log and `docs/adr/0235-stage-content-with-persisted-matches.md`.
