# Combat / match bug hunt — October 2026

Local builds and tests are prohibited by the task; regression tests below await GitHub Actions. Tests are written before each production fix; red/green execution is unverified.

| Defect | Evidence (original source) | Fix commit | Regression |
| --- | --- | --- | --- |
| Ended in-memory matches still own player membership, blocking subsequent entry and using stale teams for combat | `InMemoryMatchRepository.kt:33`; Postgres already searches active matches | `57af41d7` | `MatchRepositoryTeamLookupTest` ended-history regression; game-sim `CombatMatchBugHuntScenarioTest` second match |
| Assist history survives death, awarding stale or self assists | `AssistAttributionUseCase.kt:22-26` never clears the history or excludes the victim | `acbaf290` | `AssistAttributionUseCaseTest` life boundary and self-damage; game-sim assist life regression |
| AOE damages entities in other worlds at matching coordinates | `AoeHandler.kt:37` omits world; `PaperNearbyTargetQuery.kt:39-43` searches every world when null | `337067a0` | `AoeHandlerTest`; game-sim frost nova world isolation |
| Full-per-tick replay loses 19/20 frames; non-20-divisible keyframe intervals also skipped | `ReplayCaptureEngine.kt` ticker constant and modulo gating in `tickKeyFrames` | `e882f6d8` | `ReplayCaptureEngineTest` per-tick movement and seven-tick intervals; game-sim recorder cadence |
| Natural sudden-death expiry saves ENDED without publishing MatchEnded: no rewards, summary, return transfer or cleanup | `SwitchPhaseUseCase.kt:68-84`; `PhaseTimerUseCase.kt:106-112` calls this path | `e64b41dd` | `SwitchPhaseUseCaseTest` winner and exactly-once terminal event; game-sim natural expiry cleanup |
| Friendly-fire protection captures null before Match registers its team service | `CombatWiring.kt:434` eagerly resolves Match-owned lookup despite softdepend order; damage keeps that reference | `260a4a0a` | game-sim team assignment differs from pre-Combat host lookup, checking ally block and enemy hit |
| Match shutdown leaves phase timer and per-mode tickers running, allowing disabled matches to advance | `MatchWiring.kt:280` discards timer handle; framework registrations have no shutdown hook | `c1f98713` | game-sim shutdown leaves countdown unchanged and unregisters all modes |
| Aborting countdown publishes MatchEnded but saves WAITING, permanently holding the stage | `EndMatchUseCase.kt:32-47` uses countdown Cancel transition (a reset) as an end operation | `7a0f7a8e` | `EndMatchUseCaseTest` cancelled countdown and duplicate end; game-sim stage reuse |
| Status YAML default_duration_ticks is parsed then discarded; omitted durations always become 100 ticks | `StatusEffectYamlConfig.kt:23,35-43`; `ApplyStatusUseCase.kt:57` hardcodes default | pending | `ApplyStatusUseCaseTest` config and explicit override; game-sim shipped poison lasts 200 ticks |
