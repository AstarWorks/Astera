---
status: Accepted
date: 2026-05-27
deciders: orchestrator (Wave 5game-complete, Round 10+11)
supersedes: null
superseded_by: null
---

# ADR-0121: Game system completion — match-end wiring, PvP death policy, countdown / end summary, loadout selector GUI, min-player guard, E2E scenario harness

## Status

Accepted

## Context

Round 10 (Wave _persist-1 / 5lobby-content / 5block-embed) closed the
persistence and lobby-content audit gaps but the game-system audit
catalogued 7 P0–P1 items still open at HEAD `587c387`:

1. **Match-end wiring (Critical, §A).** `AnnihilationGameMode.onTick`
   called `tracker.evaluateWinCondition(...)` but the result was
   `@Suppress("UNUSED_VARIABLE")`. Annihilation matches could only end
   via `/astera-match end` admin command. The Wave 5annihilation +
   Wave 5complete pieces were all in place; the call into
   `IMatchLifecycle.end(...)` was the missing nail.
2. **Loadout selector GUI (§B).** Wave 5e landed the loadout domain +
   `ILoadoutRepository` but there was no in-game UI for picking
   between saved loadouts; the lobby compass tile still pointed at a
   legacy `weapons.gui.loadout.open` intent that has no handler since
   the items-plugin rename.
3. **Match countdown (§C).** Players got no visual cue between
   COUNTDOWN and ACTIVE; the existing
   `HudUpdateOnMatchPhaseChangedListener` fires only on phase change,
   not on a "5..4..3..2..1" cadence.
4. **Match end summary (§D).** `MatchEnded` had no broadcaster — the
   Wave 5stats `RewardOnMatchEnded` listener wrote to logs but
   players saw nothing on screen.
5. **PvP death policy (§E).** PvE deaths went through
   `DeathPenaltyRule` (zone-scoped). PvP-in-match deaths had no
   policy — every kill silently kept the inventory, with no per-mode
   variation (Norma keep / Annihilation strip equipped / Bet 1v1 lose
   all).
6. **Mode min-player guard (§F).** A match with 0 enrolled players
   could be advanced COUNTDOWN→ACTIVE — no defensive gate.
   Annihilation specifically requires 3-team formation but the
   `StartMatchUseCase.execute("annihilation")` path accepted any
   roster size.
7. **E2E scenario harness (§G).** Round 8 deferred a match-plugin
   scenario harness as a trade-off; the audit re-flagged it as P0
   because the integration boundaries crossed by §A–§F otherwise have
   no compile-time integration coverage.

Closing all seven in one wave keeps the round-11 PoC closing concentrated
on **gameplay-loop completeness** (the "Phase 1 PoC end-to-end" milestone
in `IMPLEMENTATION-ROADMAP.md` §Tier 7).

## Decision

Land each gap as a thin slice (no new Konsist rules, no DB migration):

### §A — Match-end wiring

New `AnnihilationWinConditionListener` in
`plugin:match/.../application/eventbus/listeners/victory/`
subscribes to:

- `core.domain.event.TeamEliminated` — fires from the tracker after a
  Tier 3 pillar falls; re-asks
  `tracker.evaluateWinCondition(matchId, atTick)` and, on
  `Winner` / `Draw`, calls
  `IMatchLifecycle.end(matchId, EndReason.ObjectiveCompleted)`.
- `core.domain.event.AnnihilationPhaseTransitioned` — on
  `Phase4SuddenDeath` entry schedules a one-shot
  `IScheduler.runLater(suddenDeathTicks)` task that re-asks the
  tracker and ends with `EndReason.SuddenDeathResolved`.

The tracker stays read-only (pure decision surface); the listener owns
the side-effect.

### §B — Loadout selector GUI

- `core.application.gui.lobby.LoadoutSelectorGuiSpec` — 9-slot
  `CHEST_9` row, each slot's left/right/shift/middle click maps to one
  of `loadout.selector.{activate,rename,reset,save}` with `slot` +
  `mode` payload keys.
- `content/gui/loadout-selector.yaml` — reload-aware author-editable
  copy of the spec; per-row `mode` payload variants distribute the 9
  slots across `norma` (×4) / `annihilation` (×3) / `bet-1v1` (×2)
  defaults so the matchmaker can resolve "which loadout for this
  mode" at queue time.
- `LoadoutSelectorShowIntentHandler` — opens the spec on the
  `loadout.selector.show` intent; mirrors the Wave 5lobby-content
  `LobbyCustomizeShowIntentHandler` shape. **Plugin wiring is
  intentionally deferred** to the same future wave that will wire the
  9 lobby intent handlers landed by ADR-0119 (currently also
  unwired).
- `CustomizeMenuGuiSpec` slot 2 repointed from legacy
  `weapons.gui.loadout.open` to `loadout.selector.show`.
- New `gui-tile-loadout` item def + i18n keys (en + ja).

### §C — Match countdown

`MatchCountdownListener` subscribes `MatchStarted` and schedules five
1-second-apart `HudLayout.Subtitle` pushes per participant ("5 → 4 →
3 → 2 → 1"). Subtitle layout dominates the screen for ~0.75 s per
beat without colliding with the existing action-bar HUD stack.

### §D — Match end summary

`MatchEndSummaryListener` subscribes `MatchEnded` and pushes:

- per-team `HudLayout.Subtitle` verdict — "Victory!" / "Defeat" /
  "Draw" keyed on `event.winningTeam`,
- per-player chat header `astera.match.summary.header` followed by
  `astera.match.summary.reason.<reason-kind>` so the
  six `EndReason` variants map to localised strings without listener
  string formatting.

GUI summary screen (MVP / per-player kills + damage breakdown) is
**deferred to Wave 6b / Phase 4** per Wave 5game-complete scope
limit; chat + subtitle is the Phase 1 PoC surface.

### §E — PvP death policy

- New `match.domain.death.PvpDeathRule` sealed interface, four
  variants: `KeepInventory` / `DropAll` / `DropEquipped` /
  `DropOnly(percent, includes)`.
- `IPvpDeathPolicyResolver` outbound port +
  `DefaultPvpDeathPolicyResolver` (in-mem); resolution chain is
  **stage-override → mode-default → KeepInventory (safest fallback)**.
- `ApplyPvpDeathPolicyListener` subscribes
  `combat.domain.event.PlayerKilled`, fast-skips non-match deaths,
  resolves the rule, translates into `IInventoryMutator.clear(...)`
  calls (`ClearScope.All` / `ClearScope.Armor` + `ClearScope.OffHand`).
  Main-hand drop and per-item probability rolls are **declared in the
  rule but applied by Wave 6 PaperInventoryMutator** (the in-mem
  variant has no live held-slot or RNG context).

Per-mode defaults:

| mode key | rule |
|---|---|
| `null` / `"norma"` / unknown | `KeepInventory` |
| `"annihilation"` | `DropEquipped` |
| `"bet-1v1"` | `DropAll` |

### §F — Mode min-player guard

- `match.domain.match.MatchMinPlayerPolicy` (pure object): pure check
  of `Match` roster vs. mode floor (Norma 2 / Annihilation 3 / Bet
  1v1 2 / unknown 2).
- `IMatchModeRegistry` outbound port + `InMemoryMatchModeRegistry`:
  per-match `modeKey` lookup; subscribed by `AsteraMatchPlugin` to
  mirror `MatchStarted.modeKey` (and `unregister` on `MatchEnded`).
- `SwitchPhaseUseCase` consults the policy on
  `CountdownComplete` triggers only. On floor miss returns the new
  `SwitchPhaseError.InsufficientPlayers(required, actual)` so the
  caller can keep the match in COUNTDOWN waiting for slots to fill.

Pre-existing `SwitchPhaseUseCaseTest` / `PhaseTimerUseCaseTest` were
updated to seed 1-per-team rosters (their original `emptySet()` was
incidental, not load-bearing).

### §G — E2E scenario harness

- `match/scenario/AsteraGameplayScenarioTest.kt` (`MatchScenarioHarness`)
  — JVM-only end-to-end harness that wires the lifecycle use cases +
  all four new listeners + the modeRegistry. Mirrors the Wave _infra-1
  `stats/scenario/AsteraTestHarness` but lives in `:plugin:match`
  so it can stay free of stats deps.
- Three scenarios:
  - `NormaTwoVTwoWinScenarioTest` — Norma 2v2 full event chain
    (`MatchStarted` ×1 + `PlayerKilled` ×2 + `MatchEnded` ×1 +
    final phase = ENDED) + a separate test proving the min-player
    guard rejects COUNTDOWN→ACTIVE with only 1 enrolled player.
  - `AnnihilationThreeVThreeCoreBreakScenarioTest` — 9 players /
    3 teams; asserts mode registry round-trip, the
    `MatchMinPlayerPolicy.requirementFor("annihilation") == 3` floor,
    PvP default = `DropEquipped`, full lifecycle chain. Second test
    proves the floor rejects 2-player attempts.
  - `InventorySnapshotRestoreScenarioTest` — save / disconnect /
    reconnect round-trip via `SaveInventorySnapshotUseCase` +
    `RestoreInventorySnapshotUseCase`, field-by-field snapshot
    equality, plus the "no prior snapshot → success(null)" path.

Plus unit tests for the new domain types (`MatchMinPlayerPolicy`,
`PvpDeathRule`, `DefaultPvpDeathPolicyResolver`).

## Consequences

### Positive

- **Annihilation matches now actually end.** `evaluateWinCondition` is
  no longer a read-only suppression; sudden-death timer +
  team-elimination both close matches with the right `EndReason`.
- **Players get visual feedback** for match start / end (subtitle +
  chat) without waiting on Wave 6b's full HUD adapter polish.
- **Mode-specific PvP behaviour** lands as a port-shaped seam.
  Phase 6 RNG-driven `DropOnly` will reuse the same resolver +
  listener, only the Paper adapter side needs the new code path.
- **Min-player floor** prevents the COUNTDOWN→ACTIVE crash mode where
  Annihilation tried to seed 0 pillar teams.
- **Scenario harness** gives the next 3 waves an integration test
  surface for free; future cross-cutting listeners can subscribe and
  assert without spinning up Paper.

### Negative / Trade-offs

- **DropOnly is shipped but inert** — the listener logs and skips when
  the rule is `DropOnly`. The intent + tests are forward-compatible;
  the actual probability roll lives in the Wave 6 adapter where
  per-item drop spawning happens.
- **GUI summary screen still missing** — Phase 1 PoC ships text-only.
  Wave 6b / Phase 4 will add a `GuiSpec` with per-player stats.
- **LoadoutSelectorShowIntentHandler not wired** — consistent with the
  9 unwired ADR-0119 lobby intent handlers; both wire-ups land in the
  same future wave (lobby intent activation wave).
- **`SwitchPhaseUseCase` ctor gained a nullable parameter.** Existing
  callers continue to compile (default `null`); the wave-wide wiring
  in `AsteraMatchPlugin` passes the live registry. Two pre-existing
  unit tests had to seed 1-per-team rosters; this is a wash for
  test clarity (the tests were arguably already wrong to assert on
  empty rosters).

### Mitigations

- **DropOnly forward-compat**: documented in `PvpDeathRule.DropOnly`
  KDoc that Wave 6 adapter is the activator; tests already assert
  it's accepted by the resolver.
- **GUI summary deferral**: chat fan-out covers the "did I win, why
  did the match end?" surface every PvP server needs; the visual
  upgrade is purely additive when it lands.
- **Listener wiring discipline**: every new listener cancels via the
  existing `subscriptions.forEach { it.cancel() }` block in
  `onDisable`; the wiring uses the same `subscriptions += …` pattern
  to keep cleanup uniform.

## Alternatives Considered

- **Push match-end-call into `AnnihilationMatchTracker`** — rejected
  because the tracker would need to depend on `IMatchLifecycle`,
  losing testability. Listener-based decision/action split mirrors
  the existing `NormaVictoryConditionEvaluatorListener` pattern.
- **PvP death policy as part of `DeathPenaltyRule` (PvE)** — rejected
  because zone-scoped PvE rules don't compose cleanly with per-match
  per-mode PvP rules; the two are different lifetime + scope axes
  and would force every PvE caller to special-case the PvP variant.
- **Min-player gate at `StartMatchUseCase`** — rejected because
  `StartMatchUseCase` creates the match in WAITING with 0 players
  (entry is a separate use case). The natural gate is at
  COUNTDOWN→ACTIVE — the last chance to keep the match open for
  more enrollments instead of cancelling.
- **Schedule MatchCountdownListener via a single 100-tick
  `runRepeatingTicks` checking elapsed** — rejected because the
  current per-second `runLater` series is dead simple and survives
  `onDisable` cancellation via the existing scheduled-task list. The
  repeating variant would need extra state to track the current
  countdown number.

## References

- Predecessor in this round: ADR-0118 (in-match HUD), ADR-0119 (lobby
  intent handlers), ADR-0120 (block command/event embedding).
- Annihilation foundation: ADR-0108 (Annihilation mode), ADR-0117
  (PvP server completeness axes — §Axis 5 win-condition driver).
- HUD primitive: ADR-0097 (custom HUD foundation), ADR-0118 (in-match
  HUD listener wireup).
- Loadout domain: Wave 5e (RTM issue #5), ADR-0073 (item-first
  rename — replaced legacy `weapons.gui.loadout.*` intents).
- Inventory port: ADR-0111 (`IInventoryMutator` + `ClearScope`).
- Scenario harness precedent: Wave _infra-1 / ADR-0059 (stats
  scenario harness).
- Konsist: no new rules; existing
  `tools/architecture-test/.../ArchitectureTest.kt` 52 rules unchanged
  and pass.
- Roadmap: `docs/IMPLEMENTATION-ROADMAP.md` Tier 7 (Phase 1 PoC end-
  to-end). RTM: `docs/RTM-PARITY-TRACKER.md` rows for "match end
  flow" / "PvP death policy" / "loadout authoring".
