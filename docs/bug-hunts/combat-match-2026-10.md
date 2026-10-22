# Combat / match bug hunt — October 2026

Local builds and tests are prohibited by the task; regression tests below await GitHub Actions. Tests are written before each production fix; red/green execution is unverified.

| Defect | Evidence (original source) | Fix commit | Regression |
| --- | --- | --- | --- |
| Ended in-memory matches still own player membership, blocking subsequent entry and using stale teams for combat | `InMemoryMatchRepository.kt:33`; Postgres already searches active matches | `57af41d7` | `MatchRepositoryTeamLookupTest` ended-history regression; game-sim `CombatMatchBugHuntScenarioTest` second match |
| Assist history survives death, awarding stale or self assists | `AssistAttributionUseCase.kt:22-26` never clears the history or excludes the victim | pending | `AssistAttributionUseCaseTest` life boundary and self-damage; game-sim assist life regression |
