# Core / Items bug hunt — October 2026

Verification: no local builds or tests run, per task constraints. Regression tests are for GitHub Actions.

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
- Evidence: `EnchantmentGateListener.kt:48` removed kept stacks from drops, with no respawn buffer or `itemsToKeep` write; its comment explicitly deferred restoration.
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
- Fix commit: pending.
