# Core / Items bug hunt — October 2026

Verification: no local builds or tests run, per task constraints. All 15 regression tests were written before their corresponding production fixes; failing/passing execution is unverified until GitHub Actions. `git diff --check` is the only local diff validation.

Evidence locations refer to baseline `8c2481ad` (before these fixes); filenames are unique under the two plugins.

Coverage: all 53 Items production Kotlin files and the 18 Core platform/wiring files were read, with targeted tracing into Core services, ports, domain types and tests. All 1,426 Core/Items production Kotlin files were scanned for literal MessageKey references; apparent misses were checked against quoted/nested language keys and were not missing translations. **The requested exhaustive body-by-body review of Core's 1,373 production Kotlin files is not complete.** This report records confirmed fixes, not a clean bill of health for the entire Core module.

## 1. Saved language preference ignored
- Evidence: `CoreWiring.kt:244` resolved only the Minecraft client locale; `SetLocaleUseCase.kt:29` persisted a setting no renderer read.
- Fix: consult the registered settings repository on rendering, falling back to the client locale when no settings row exists. Lazy lookup preserves bootstrap order and the persistence overlay.
- Test: `LocalePreferenceScenarioTest` switches Japanese / English through the use case under production wiring.
- Fix commit: `ce18331a`.

## 2. Resource regeneration keeps ticking departed players
- Evidence: `CoreWiringPlayer.kt:130` tracked joins without subscribing to quits; `DefaultResourceRegenScheduler.kt:47` had an unused untrack path. Every departed player continued producing regeneration events and consuming per-tick work.
- Fix: untrack on quit (also clears fractional accumulators), resume on join, and retain/cancel the repeating task at shutdown.
- Test: `ResourcePoolScenarioTest.quit stops resource regeneration and a rejoin resumes it` checks the tracked set and absence of offline events.
- Fix commit: `8c5db480`.

## 3. keep_on_death destroys tagged items
- Evidence: `EnchantmentGateListener.kt:49` removed kept stacks from drops, with no respawn buffer or `itemsToKeep` write; its comment explicitly deferred restoration.
- Fix: hand retained stacks to Paper's `itemsToKeep`; skip when keepInventory already preserves the complete inventory to prevent duplicates.
- Test: `EnchantmentGateListenerTest` checks drop/retention lists and the keepInventory path with mocked Paper events. Uses existing catalog Paper/MockK test dependencies.
- Fix commit: `bd1c2040`.

## 4. Refinement accepts unrelated item instances
- Evidence: `ApplyRefinementUseCase.kt:73` looked up only by UUID, without checking owner or item template; `RerollSubStatsUseCase.kt:79` likewise ignored owner. A caller could mutate another player's item or apply a permissive template's rules to another template.
- Fix: require the requested owner/template on lookup before mutation, cost or event publication. Return the existing UnknownInstance error for inaccessible instances.
- Tests: two apply regressions (foreign owner / mismatched template) and one reroll regression (no charge, no roll, no mutation/event for a foreign owner).
- Fix commit: `21c4d9be`.

## 5. Refinement profiles never load at startup
- Evidence: `ItemsWiring.kt:131` constructed an empty RefinementProfileRegistry, immediately handing its resolver to both use cases. No production caller invoked register/registerFromYaml, despite the documented `content/refinement/<id>.yaml` contract.
- Fix: use the existing multi-format ContentLoader to populate that registry before constructing consumers; malformed profiles are logged and skipped through the standard loader.
- Test: `RefinementContentScenarioTest` boots real wiring with a valid profile and malformed sibling, then actually applies the configured refinement. Its temporary content tree links the existing categories and owns only refinement.
- Fix commit: `982c406e`.

## 6. BCP-47 locales fall back to English for key-based messages
- Evidence: `SimpleMessageRenderer.kt:104` split only on underscore and did not trim, unlike LocaleCode.parse used by inline item text. A `ja-JP` viewer received English message-key text alongside Japanese item text.
- Fix: reuse LocaleCode.parse for both paths.
- Test: `SimpleMessageRendererTest.key and inline translations agree for trimmed BCP47 locales` covers key text, lore, and inline text with `  JA-JP  `.
- Fix commit: `57e31858`.

## 7. Short effect sequences cancel longer parallel branches / loop iterations
- Evidence: `InMemoryEffectOrchestrator.kt:70` scheduled markComplete for each Sequence against the same CompositeHandle; `dispatchStep` skipped all subsequent tasks once that flag flipped.
- Fix: compute the latest end tick while scheduling the tree, then mark the shared handle complete once, after all branches/iterations finish.
- Tests: `InMemoryEffectOrchestratorTest` covers unequal parallel sequences and a delayed sequence repeated in a loop, including active/completed lifecycle assertions.
- Fix commit: `9f56e339`.

## 8. Match configuration writes to discarded in-memory world gateways
- Evidence: `CoreWiringWorld.kt:199` passed local in-memory rules/time/weather instances to InMemoryMatchConfigService after registering host overlays above. Applying a match config reported success but never changed the Paper world.
- Fix: resolve the three final gateways from the registry when constructing the service.
- Test: `MatchConfigWiringTest` supplies host gateways to the production wiring stage and checks apply/reset reach those exact gateways (PvP, difficulty, inventory, time, weather).
- Fix commit: `e1f6b2b7`.

## 9. Missing hand-off snapshot is treated as up-to-date
- Evidence: `PlayerHandoffService.kt:188` used `snapshot == null || ... >= token.version`; a missing/read-failed snapshot immediately unlocked arrival instead of waiting for the source version. Match entry could then capture the default inventory over the saved inventory.
- Fix: a token requires a non-null snapshot at least its version; missing reads retry within the existing bounded wait.
- Test: `PlayerHandoffServiceTest.a handoff token waits for a snapshot that is not visible yet` delays visibility and checks Loading/no arrival until the inventory is actually restored.
- Fix commit: `009f402f`.

## 10. Expired early hand-off tokens bypass the next join's wait
- Evidence: `PlayerHandoffService.kt:134` consumed stored tokens without checking TTL; purgeTokens only ran when another token arrived. An abandoned transfer token could therefore unlock a much later join before its current token arrived.
- Fix: expire cached tokens before consuming an early token on join.
- Test: `PlayerHandoffServiceTest.an expired early token cannot complete a later join` advances beyond TTL, verifies the new join remains Loading, then delivers its fresh token.
- Fix commit: `9b010712`.

## 11. Same-tick completion suppresses final effect steps
- Evidence: `InMemoryEffectOrchestrator.kt:101` gated dispatch on isActive, but the completion callback shares the final step's tick. Schedulers do not promise FIFO ties (the existing FakeScheduler uses a PriorityQueue), so completion can run before final callbacks.
- Fix: suppress scheduled effects only on explicit cancellation; a completion flag is not cancellation. Whole-chain end timing remains unchanged.
- Test: `InMemoryEffectOrchestratorTest.completion at the same tick cannot suppress final steps` schedules three equal-time steps against the existing scheduler.
- Fix commit: `6688b5a7`.

## Remaining review leads (not counted as fixed defects)

- `CoreWiringContent.kt:175` captures no-op effect appliers. Repository-wide references show no production Combat registration of either effect SPI, despite the documented contract. A complete fix needs the Combat owner; changing only Core's lookup would leave the feature inert.
- `IAfkDetector.recordActivity` has no production callers. Trace Paper activity signals and local online-player membership before wiring AFK enforcement; blindly seeding joins would kick active lobby players because lobby movement is not published as PlayerMoved.
- Refinement/glow contains further unconsumed surfaces (no Paper glow renderer / held-glow invocation, refined-power container has no runtime consumer). Establish intended shipped scope before extending these foundations.
- Continue the full Core application/domain/adapter body review, particularly session ownership across lobby/game, inventory mutation gateways, permission backend selection, content reload consumers and shutdown hooks.
