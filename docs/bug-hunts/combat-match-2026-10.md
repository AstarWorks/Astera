# Combat / match bug hunt — October 2026

Local builds and tests are prohibited by the task; regression tests below await GitHub Actions. Tests are written before each production fix; red/green execution is unverified.

| Defect | Evidence (original source) | Fix commit | Regression |
| --- | --- | --- | --- |
| Ended in-memory matches still own player membership, blocking subsequent entry and using stale teams for combat | `InMemoryMatchRepository.kt:38`; Postgres already searches active matches | `57af41d7` | `MatchRepositoryTeamLookupTest` ended-history regression; game-sim `CombatMatchBugHuntScenarioTest` second match |
| Assist history survives death, awarding stale or self assists | `AssistAttributionUseCase.kt:22-26` never clears the history or excludes the victim | `acbaf290` | `AssistAttributionUseCaseTest` life boundary and self-damage; game-sim assist life regression |
| AOE damages entities in other worlds at matching coordinates | `AoeHandler.kt:37` omits world; `PaperNearbyTargetQuery.kt:39-43` searches every world when null | `337067a0` | `AoeHandlerTest`; game-sim frost nova world isolation |
| Full-per-tick replay loses 19/20 frames; non-20-divisible keyframe intervals also skipped | `ReplayCaptureEngine.kt:367` ticker constant and modulo gating in `tickKeyFrames` | `e882f6d8` | `ReplayCaptureEngineTest` per-tick movement and seven-tick intervals; game-sim recorder cadence |
| Natural sudden-death expiry saves ENDED without publishing MatchEnded: no rewards, summary, return transfer or cleanup | `SwitchPhaseUseCase.kt:68-84`; `PhaseTimerUseCase.kt:106-112` calls this path | `e64b41dd` | `SwitchPhaseUseCaseTest` winner and exactly-once terminal event; game-sim natural expiry cleanup |
| Friendly-fire protection captures null before Match registers its team service | `CombatWiring.kt:436` eagerly resolves Match-owned lookup despite softdepend order; damage keeps that reference | `260a4a0a` | game-sim with `standaloneTeams = false` boots without the simulator-only team override, checking ally block and enemy hit |
| Match shutdown leaves phase timer and per-mode tickers running, allowing disabled matches to advance | `MatchWiring.kt:302` discards timer handle; framework registrations have no shutdown hook | `c1f98713` | game-sim shutdown leaves countdown unchanged and unregisters all modes |
| Aborting countdown publishes MatchEnded but saves WAITING, permanently holding the stage | `EndMatchUseCase.kt:32-47` uses countdown Cancel transition (a reset) as an end operation | `7a0f7a8e` | `EndMatchUseCaseTest` cancelled countdown and duplicate end; game-sim stage reuse |
| Status YAML default_duration_ticks is parsed then discarded; omitted durations always become 100 ticks | `StatusEffectYamlConfig.kt:23,35-43`; `ApplyStatusUseCase.kt:57` hardcodes default | `1ab3d1b7` | `ApplyStatusUseCaseTest` config and explicit override; game-sim shipped poison lasts 200 ticks |
| Game assignment retains arrivals after disconnect, starting with offline ghost participants | `MatchAssignmentReceiver.kt:71-76,104-105` subscribes to arrival/end but never quit | `10c2f019` | `CrossServerHandoffTest` departed and rejoined arrivals; game-sim GAME assignment drops after sole arrival quits |

## Scope and design

Read all 476 existing production files under `plugin/combat/src/main` and `plugin/match/src/main` (474 Kotlin files and two plugin descriptors), including every `platform/wiring` and Paper binding. Source baseline: `8c2481ad46bd56933e42dfdbdbb8bed11f238581`. Cross-checked service lookup order, event publishers/subscribers, repository overlays, shipped skill/status content, locale key paths, and ADR-0179 / ADR-0195.

| Module | Adapter | Application | Command | Domain | Platform | Resources |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| combat | 23 | 126 | 1 | 87 | 5 | 1 |
| match | 6 | 146 | 2 | 69 | 9 | 1 |

- Keep finished match history; player membership queries match the existing Postgres active-only behavior.
- Resolve the Match-owned team port per damage call, using the lazy lookup pattern already used by combat mechanics. `standaloneTeams = false` prevents the simulator's priority-100 team fake from masking Paper's enable order.
- Publish `MatchEnded` only on successful terminal transitions. Explicit `end` always persists ENDED; state-machine countdown reset remains available through `switchPhase(Cancel)`.
- Preserve authored status defaults in the domain spec; explicit application durations still take precedence.
- No new dependencies, compatibility layers, DB changes, or architectural decisions requiring an ADR.

## Validation and limits

Added 21 regression methods: 11 unit tests and 10 game-sim scenarios. Each production fix was preceded by its regression source; no red/green execution was possible under the local build/test prohibition. The final simulator fixture adjustment makes the late-team regression reproduce Paper's missing pre-Combat lookup.

Only source inspection and Git diff checks were performed locally. No Gradle, builds, test suites, Docker, or E2E commands were run. Compilation, existing tests, Konsist, runtime Paper behavior, and distributed Postgres/Redis behavior await the orchestrator's push and GitHub Actions. Locale files, existing migrations, `content/models`, and `private` were untouched. No push was performed.
