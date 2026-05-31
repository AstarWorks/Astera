---
status: Accepted
date: 2026-05-31
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0139: Onboarding rotating tip on PlayerJoinedServer

## Status

Accepted

## Context

Round 18 audit α (Explore agent #1) initially claimed "first-time profile
creation flow missing — Onboarding flow incomplete. Players get no welcome
sequence, no setup guidance." Direct verification proved this **partially
false**:

- ✅ `ShowWelcomeDialogOnFirstTimeProfileCreatedListener` exists
  (`plugin/core/.../application/eventbus/listeners/onboarding/`) and
  subscribes `FirstTimeProfileCreated`, calls `IDialogGateway.showDialog`
  with a configured welcome `DialogSpec` resolved via
  `InMemoryPlayerProfileService.findWelcomeDialog(playerId)`.
- ✅ `StartTutorialOnFirstTimeProfileCreatedListener` exists (same dir)
  and subscribes `FirstTimeProfileCreated`, calls
  `ITutorialSequencerService.startSequence(...)` with the recorded
  tutorial enrollment id.
- Both are wired in `AsteraCorePlugin.onEnable` (lines 1794, 1802 —
  `wave5completeSubs += eventBus.subscribe(FirstTimeProfileCreated::class)
  { ... }`).

So the audit claim was a **false alarm** for the one-shot first-time
onboarding path. However, verification surfaced a related genuine gap:

- ❌ No "tip of the moment" / "rotating help" surface for the **ongoing**
  case (every login, not just first-time). The 3 existing
  `PlayerJoinedServer` listeners cover narrow concerns:
  `RestoreInventoryOnPlayerJoinedListener` (Wave 5e inventory restore),
  `CreateFirstTimeProfileOnPlayerJoinedListener` (kicks off the
  first-time chain above), and `UpdatePopulationHudOnPlayerJoinedListener`
  (Wave M sidebar count). None of them surface discovery content for
  experienced players — who frequently miss niche features (party
  invite, refinement tier, survival islands) simply because nothing in
  the UI cues them.

The 6-message rotating tip closes that gap with a low-cost, zero-Paper
listener that sends a single chat line per join, cycling through a fixed
catalogue.

## Decision

Add `HintOnPlayerJoinedListener` in
`plugin/core/.../application/eventbus/listeners/onboarding/`:

- Constructor: `IPlayerGateway` + `IMessageRenderer` (existing
  `NotifyOnRecipeCraftedListener` pattern — Wave H 5content-depth).
- `handle(event: PlayerJoinedServer)`:
  - Pick a tip index via `AtomicInteger.getAndIncrement() mod
    HINT_KEYS.size` — round-robin, per-process monotonic, deterministic
    for tests.
  - Render the tip via `IMessageRenderer.render(playerId, key)` so each
    player sees their locale.
  - Send via `IPlayerGateway.sendMessage(playerId, rendered)`.
  - Swallow `RuntimeException` from render/send with a WARN log (tip is
    best-effort UX, not gameplay-critical).
- Catalogue: 6 i18n keys `astera.tip.1` … `astera.tip.6` covering the
  most-missed lobby features (menu navigation, hotbar bind, party
  invite, achievement tracking, item refinement, survival islands).
- Wired in `AsteraCorePlugin.onEnable` right after the existing
  `populationJoinedListener` / `populationQuitListener` blocks.
- Unit test verifies (a) 8 sequential `handle()` calls produce the
  expected `1→2→3→4→5→6→1→2` rotation and (b) `handle` swallows
  renderer exceptions (assertion: `playerGateway.sent` is empty when
  renderer throws).

Why a round-robin AtomicInteger instead of random selection:

- CLAUDE.md §Coding posture forbids `Math.random()` in domain /
  application code (reproducibility).
- AtomicInteger gives deterministic test assertions without needing a
  seeded RNG injection.
- Players joining within the same minute see different tips — same
  player rejoining sees a different tip than their first join. Adequate
  variety for Phase 1 PoC.

## Consequences

### Positive

- Closes the discovery gap for ongoing players (the one-shot first-time
  onboarding already covered new accounts; this fills in the recurring
  surface).
- 6 high-value tip texts (en + ja) cover the lobby features Round 18
  audit α identified as "discoverable but unsignaled" (party, refinement,
  islands).
- Zero infrastructure burden — uses existing `IMessageRenderer` +
  `IPlayerGateway` ports; no new ports / no Paper dependency.
- Tests are deterministic — round-robin sequence is reproducible across
  CI runs.
- Forward-compat — adding a 7th tip is a 1-line catalogue append + 2 i18n
  entries; no rule changes needed.

### Negative / Trade-offs

- Per-process counter, not per-player. Two players joining in succession
  see consecutive tips (1, 2), not "their next tip". Acceptable for
  Phase 1 PoC (the catalogue is small and variety is the goal, not
  spaced repetition).
- Tip catalogue is fixed in source (not authored via YAML registry).
  Adding/removing tips requires recompile. Phase 1 PoC tolerates this;
  Phase 2+ could promote to `IHintRegistry` + content YAML if marketing
  / live-ops cadence demands it.
- No "dismiss tip" / "stop showing tips" preference. Players who don't
  want tips have no opt-out. Mitigated by tip being a single short chat
  line (low friction).

### Mitigations

- Catalogue is centralised on `HintOnPlayerJoinedListener.HINT_KEYS`
  companion — adding a 7th tip is a single source edit.
- All tip i18n keys go through the existing `i18n keys must match
  across en and ja language files` Konsist rule — adding a tip in
  English without ja sibling fails CI.
- Per-process counter is acceptable because Astera servers don't
  restart per-tip; the counter survives across joins within a session.

## Alternatives Considered

- **Alt A: random tip selection with seeded RNG**: rejected. Adds RNG
  injection burden across the wiring; the determinism benefit of the
  counter outweighs the freshness benefit of randomness.
- **Alt B: per-player counter persisted to player session**: rejected
  for Phase 1 PoC. Requires schema column + load/save flow. Round 18 is
  scoping for "minimum useful" not "production-quality personalisation".
  Phase 2+ can promote.
- **Alt C: tip via title/subtitle instead of chat**: rejected. Title is
  consumed by other join surfaces (welcome dialog open animation);
  stacking tips on title creates flicker. Chat is the canonical
  rotating-help surface in MC servers.
- **Alt D: skip Wave AC entirely (onboarding flow exists)**: rejected.
  The first-time flow only fires per-account; ongoing players get no
  rotating discovery. Wave AC's narrow scope (1 listener + 6 i18n keys
  + 2 hours) is well worth shipping.

## References

- 関連 ADR:
  [[adr/0022-multi-plugin-layout]],
  [[adr/0116-onboarding-first-time-flow]] (Wave 5wireup — established
  the first-time `FirstTimeProfileCreated` listeners this ADR is
  parallel to),
  [[adr/0125-content-depth-recipes]] (Wave H — established the
  `IMessageRenderer` + `IPlayerGateway.sendMessage` pattern that
  `HintOnPlayerJoinedListener` follows),
  [[adr/0134-konsist-event-publisher-exists-rule]] (Round 16 Wave P —
  the `i18n keys must match` rule that protects this ADR's en/ja parity)
- Related files:
  `plugin/core/src/main/kotlin/.../application/eventbus/listeners/onboarding/HintOnPlayerJoinedListener.kt`,
  `plugin/core/src/test/kotlin/.../application/eventbus/listeners/onboarding/HintOnPlayerJoinedListenerTest.kt`,
  `plugin/core/src/main/kotlin/.../platform/AsteraCorePlugin.kt`
  (wiring around line ~2020),
  `content/languages/en.yaml` + `content/languages/ja.yaml`
  (6 new `astera.tip.*` keys)
- Round 18 audit α false-positive corrections documented here:
  - "first-time profile creation flow missing" — FALSE; flow IS wired
    via ShowWelcomeDialogOnFirstTimeProfileCreated +
    StartTutorialOnFirstTimeProfileCreated listeners
- Future work:
  - If Phase 2+ live-ops cadence demands per-event tip updates, promote
    catalogue to `IHintRegistry` + `content/tips/` YAML reload-aware
    pattern (sibling to `IAchievementRepository.registerAll`)
  - If players ask for opt-out, add `PlayerSettings.showTips: Boolean`
    field + check in the listener
