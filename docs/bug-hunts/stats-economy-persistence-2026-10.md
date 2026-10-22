# Stats / economy / persistence bug hunt — 2026-10

Local builds, tests, Docker and pushes are prohibited for this task. Regression tests are written before fixes; red/green execution is deferred to orchestrator-triggered GitHub Actions.

| Defect | Evidence | Fix commit | Regression |
| --- | --- | --- | --- |
| Deaths never recorded: RecordDeathUseCase constructed but no subscriber invokes it | `plugin/stats/src/main/kotlin/com/astarworks/astera/stats/platform/wiring/StatsWiring.kt:186`, `:203` | `fix(stats): record victim deaths on player kills` (7c952841) | RecordDeathUseCaseTest; ProgressionLoopScenarioTest player + environmental deaths |
| First stat write lost: cold find returns null, callers mutate detached PlayerStats, upsert only flushes the untouched cached container | `plugin/persistence/src/main/kotlin/com/astarworks/astera/persistence/repository/PostgresPlayerStatsRepository.kt:32`, `:39` | `fix(persistence): persist detached player stats on upsert` (this commit) | PostgresPlayerStatsRepositoryIT first mutation and detached upsert across fresh stores |
