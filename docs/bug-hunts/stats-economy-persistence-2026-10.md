# Stats / economy / persistence bug hunt — 2026-10

Local builds, tests, Docker and pushes are prohibited for this task. Regression tests are written before fixes; red/green execution is deferred to orchestrator-triggered GitHub Actions.

| Defect | Evidence | Fix commit | Regression |
| --- | --- | --- | --- |
| Deaths never recorded: RecordDeathUseCase constructed but no subscriber invokes it | `plugin/stats/src/main/kotlin/com/astarworks/astera/stats/platform/wiring/StatsWiring.kt:186`, `:203` | `fix(stats): record victim deaths on player kills` (7c952841) | RecordDeathUseCaseTest; ProgressionLoopScenarioTest player + environmental deaths |
| First stat write lost: cold find returns null, callers mutate detached PlayerStats, upsert only flushes the untouched cached container | `plugin/persistence/src/main/kotlin/com/astarworks/astera/persistence/repository/PostgresPlayerStatsRepository.kt:32`, `:39` | `dc6de4fa` | PostgresPlayerStatsRepositoryIT first mutation and detached upsert across fresh stores |
| Authored stock checked before owned-copy path and authoritative stock decrement; changing YAML remaining to zero blocks existing owners and ignores positive persisted stock | `plugin/economy/src/main/kotlin/com/astarworks/astera/economy/application/usecase/shop/ShopPurchaseUseCase.kt:79` (before fix) | `fix(economy): use live stock after resolving owned shop copies` (this commit) | ShopOwnershipAndBrowseTest owned copy with authored zero stock |
