---
status: Accepted
date: 2026-05-27
deciders: orchestrator (Wave 5wireup), user
supersedes: null
superseded_by: null
---

# ADR-0116: E2E gameplay loop wireup — close 11 listener / command / use-case gaps

## Status

Accepted

## Context

Round 1–7 of the pre-Paper roadmap (Wave 5j–5transitions, ADRs 0098–0115)
shipped every primitive port the Phase 1 PoC needs: profile service,
tutorial sequencer, dialog gateway, HUD gateway, world gateway, party
island manager, matchmaker, respawn use case, game-mode framework, …

A top-down E2E trace performed at the end of Round 7 — three Explore
agents walking the player journey (login → lobby → queue → match → death
→ respawn → reward → lobby) — identified **11 gaps where primitives
were never connected**. The match plugin defines `buildQueueCommandSpec`
but never registers it. `CreateFirstTimeProfileOnPlayerJoinedListener`
exists but is not subscribed. `AnnihilationGameMode` is registered but
the wiring layer always resolves to `NormaGameMode`. The HUD subscriber
side of `CurrencyChanged` / `LeveledUp` / `ComboStepRecorded` is empty.
`/astera travel pve|survival|lobby` and `/astera menu` simply don't
exist.

The result was a runtime that **boots cleanly and passes every Konsist
rule but cannot actually let a player play a match end-to-end**: new
joiners get no profile, deaths never respawn, queue join is unreachable,
Annihilation forms an empty team set, the post-match teleport never
happens.

The Wave 5wireup objective is to close those 11 gaps without rewriting
any port surface — pure wiring + a handful of single-purpose listeners
and use cases.

## Decision

Land Wave 5wireup as a single commit on `wave-5wireup`:

- **3 new domain events** (`FirstTimeProfileCreated`,
  `PlayerTraveledToPveWorld`, `PlayerArrivedAtIsland`,
  `PlayerReturnedToLobby`) in `core.domain.event` so the new listeners /
  use cases have something narrow to publish.
- **3 new onboarding listeners** in core
  (`CreateFirstTimeProfileOnPlayerJoinedListener` subscribe-fix + Tutorial +
  WelcomeDialog auto-start on `FirstTimeProfileCreated`).
- **3 new HUD listeners** placed per ADR-0022 sibling-isolation:
  - `CurrencyChanged` → core (event lives in core)
  - `LeveledUp` → stats (event lives in stats)
  - `ComboStepRecorded` → combat (event lives in combat)
  Each pushes a placeholder `HudLayout.ActionBar` to `IHudGateway`; the
  in-memory default just records the call so the subscriber wire is now
  in place when Wave 6b's `PaperHudGateway` lands.
- **2 new match listeners**
  (`RespawnOnPlayerKilledListener`, `TeleportToLobbyOnMatchEndedListener`)
  with their wires in `AsteraMatchPlugin.onEnable`.
- **3 new use cases** in core
  (`TravelToPveWorldUseCase`, `TravelToSurvivalIslandUseCase`,
  `ReturnToLobbyUseCase`) backing `/astera travel pve|survival|lobby`.
- **3 new command specs** in core (`TravelCommandSpec`, `HelpCommandSpec`,
  `MainMenuCommandSpec`) + **1 new GUI spec** (`MainMenuGuiSpec` +
  author-editable `content/gui/main-menu.yaml`).
- **Wire `buildQueueCommandSpec`** that already exists in match-plugin
  (G9) + instantiate + register `InMemoryMatchmaker` (which was also
  never wired).
- **`MatchStarted.modeKey: String? = null`** added so the wiring layer
  picks the right `IGameMode`; `StartMatchUseCase.execute(stageId,
  modeKey, teamIds)` selects 3-team formation when `modeKey ==
  "annihilation"`; `AsteraMatchPlugin`'s `MatchStarted` subscription
  pre-seeds `GameModeContext.scoringState["annihilation.teams"]` so
  `AnnihilationGameMode.inferTeams` finds a non-empty team set.
- **Wire foundations the listeners require but no prior wave wired**:
  `InMemoryDialogGateway`, `InMemoryHudGateway`,
  `InMemoryPlayerProfileService` (with starter-grant.yaml load via the
  existing `StarterGrantLoader`).

No port shape changes. No new Konsist rules. No DB migration. The
existing `CreateFirstTimeProfileOnPlayerJoinedListener` gains two
constructor params (`eventBus`, `scheduler`) so it can publish
`FirstTimeProfileCreated`; the one existing test is updated to pass the
fakes. `IMatchLifecycle.start(stageId)` becomes
`start(stageId, modeKey: String? = null)`; the one existing fake
implementation in `MatchFoundUseCaseTest` is updated.

## Consequences

### Positive

- **Phase 1 PoC is genuinely end-to-end runnable.** First-time join
  creates a profile, fires the tutorial, shows the welcome dialog.
  `/astera queue join norma` produces a `MatchFormed`, which feeds
  `StartMatchUseCase`, which fires `MatchStarted`, which (now) dispatches
  to the correct `IGameMode`. A kill fires `PlayerKilled`, which now has
  a subscriber that calls `RespawnUseCase`. `MatchEnded` teleports
  everyone back to the lobby. HUD subscribers exist on the three
  hot-path events so Wave 6b's Paper renderer plugs in without further
  wiring.
- **Annihilation is no longer broken.** The 3-team formation +
  `scoringState["annihilation.teams"]` seed lets
  `AnnihilationMatchTracker.register` create real pillar instances. The
  match can actually progress through its phase state machine.
- **HUD subscriber wiring respects ADR-0022.** Three subscribers, three
  plugins, each importing only its own event + core's `IHudGateway`.
  Konsist stays green.
- **The wiring change set is small.** The base concept of the wave —
  "primitives are right, just connect them" — keeps the diff narrow.
  Plugin.kt wiring edits + a handful of new files; no domain mutation
  beyond the four new events.

### Negative / Trade-offs

- **HUD listener layouts are placeholders.** Each subscriber pushes a
  single i18n key (`astera.hud.currency.summary` etc.) without
  placeholders. The renderer-side i18n catalogue still has to grow
  proper text + placeholder support. Acceptable because the wave's
  contract is "subscriber wired", not "subscriber renders perfectly".
- **`MainMenuCommandSpec` opens a GUI built from a hard-coded item-id
  set.** `gui-tile-queue` etc. resolve through the existing item
  registry; if the items are not authored, the slot renders the
  registry's fallback. The YAML at `content/gui/main-menu.yaml` is the
  author-editable surface; it overrides the in-Kotlin spec when the
  loader picks it up (Wave 5h reload-aware path).
- **`HelpCommandSpec` returns `CommandResult.Failure` to surface its
  text** (the command framework's `Success` variant is silent by
  design). Documented in the file. A proper `Reply(MessageKey)` variant
  is a small future cleanup.
- **No fallback shim for the old `IMatchLifecycle.start(stageId)`
  signature.** Per CLAUDE.md §Pre-release posture, the rename + extra
  default-null parameter happens in one commit; the one fake in
  `MatchFoundUseCaseTest` is updated in place.
- **The Annihilation 3-team default uses team ids
  `["red", "blue", "yellow"]`** with no explicit `i18n` for `"yellow"`
  yet. The translation catalogue will gain `astera.match.team.yellow.*`
  keys when language fixtures are authored — until then the renderer
  falls back to the key string.

### Mitigations

- Tests cover the four most-load-bearing new listeners (G2, G3, G4, G5)
  plus the Annihilation 3-team start (G16) and the existing
  `CreateFirstTimeProfileOnPlayerJoinedListener` constructor change.
- The HUD listener stub layouts are intentionally trivial so the
  upgrade path is a single `HudComponent` swap when the Wave 5j
  renderer-side glyph wiring lands.
- The `MainMenuCommandSpec` builder is a single function — replacing
  it with a YAML-loader-backed lookup once `IGuiRegistry` grows a
  `find` path is a single-call swap.

## Alternatives Considered

- **Push the matchmaker / IDialogGateway / IHudGateway / profile
  service into separate one-line wires across each existing wave's
  ADR.** Rejected — too many tiny ADRs, none of them describing a
  decision, and each individually unable to verify "E2E loop is now
  real". A single wave is cheaper to reason about.
- **Have all three HUD listeners live in core** (per the original
  prompt). Rejected — `LeveledUp` (stats) and `ComboStepRecorded`
  (combat) live outside core's allowed import set per ADR-0022.
  Subscriber placement follows the event's owning plugin instead.
- **Make `StartMatchUseCase` take a richer `MatchStartRequest` value
  object.** Deferred — `modeKey` + `teamIds` covers Phase 1 PoC; the
  request object lands when the mode catalogue grows per-match config
  carriers in Wave 7+ (per ADR-0108 §Future work).
- **Hand-write a `GameModeId`-typed field on `MatchStarted`.**
  Rejected — `GameModeId` lives in `:plugin:match`; core events must
  stay vendor-neutral across plugin boundaries. String-typed
  `modeKey` mirrors `MatchFormed.modeKey` (already in core).
- **Surface `FirstTimeProfileCreated` from inside
  `IPlayerProfileService.createFirstTime`.** Rejected — the service
  is a port surface that adapters may implement (Postgres overlay,
  fake, …); event publication is the listener-tier's job, mirroring
  how `RestoreInventoryOnPlayerJoinedListener` publishes
  `InventoryRestored` rather than the snapshot use case doing so.

## References

- `/home/node/.claude/plans/wave-5g-core-hardening-giggly-sunset.md` §Round 8
  (3-Explore-agent gap inventory + state × wave coverage matrix)
- ADR-0022 (multi-plugin sibling isolation — drove HUD listener
  placement)
- ADR-0030 / ADR-0058 (forward-only commit posture, no dual-decoder)
- ADR-0064 (listener vs service responsibility split — kept the
  starter-grant service pure-data; new listeners handle the runtime
  side effects)
- ADR-0081 (Wave 5o respawn + spawn protection — reused as-is)
- ADR-0088 (Wave 5p tutorial sequencer — reused as-is)
- ADR-0089 (Wave 5w matchmaker / `MatchFormed` — reused as-is)
- ADR-0096 (Wave 5i dialog gateway — reused as-is)
- ADR-0097 (Wave 5j HUD gateway — reused as-is)
- ADR-0107 (Wave 5pve-survival party-island manager — reused as-is)
- ADR-0108 (Wave 5annihilation game-mode framework — the wave
  populating `scoringState["annihilation.teams"]` was the prereq the
  wiring side completed here)
