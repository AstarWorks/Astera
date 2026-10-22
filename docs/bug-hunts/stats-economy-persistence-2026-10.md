# Stats / economy / persistence bug hunt — 2026-10

Local builds, tests, Docker and pushes are prohibited for this task. Regression tests are written before fixes; red/green execution is deferred to orchestrator-triggered GitHub Actions.

| Defect | Evidence | Fix commit | Regression |
| --- | --- | --- | --- |
| Deaths never recorded: RecordDeathUseCase constructed but no subscriber invokes it | `plugin/stats/src/main/kotlin/com/astarworks/astera/stats/platform/wiring/StatsWiring.kt:186`, `:203` | `fix(stats): record victim deaths on player kills` (this commit) | RecordDeathUseCaseTest; ProgressionLoopScenarioTest player + environmental deaths |
