# Core / Items bug hunt — October 2026

Verification: no local builds or tests run, per task constraints. Regression tests are for GitHub Actions.

## 1. Saved language preference ignored
- Evidence: `CoreWiring.kt:244` resolved only the Minecraft client locale; `SetLocaleUseCase.kt:29` persisted a setting no renderer read.
- Fix: consult the registered settings repository on rendering, falling back to the client locale when no settings row exists. Lazy lookup preserves bootstrap order and the persistence overlay.
- Test: `LocalePreferenceScenarioTest` switches Japanese / English through the use case under production wiring.
- Fix commit: pending.
