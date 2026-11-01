---
status: Accepted
date: 2026-11-01
deciders: founder, manager (astera-04)
supersedes: null
superseded_by: null
---

# ADR-0319: Norma tool progression and the Annihilation 5-phase flow

## Status

Accepted

## Context

Two founder requests of 2026-11-01 land together because both reshape how a match paces itself.

1. Loaned match tools (the pickaxe / axe / shovel every player gets) were bought on a shop tab, so a tool tier was a gold sink instead of a
   statement of progress. The Norma level already says how far a player has come.
2. 「アナイアレーションのゲームモードを完璧にして。ちゃんとナビゲーションがあって楽しいか」: the old Annihilation had four phases
   (build, engage, tier 3, sudden death), no on-screen sense of "what now", Mid ore open from the first second, and a sudden-death DoT that
   hit every pillar equally on a per-tick scale nobody could read.

## Decision

### 1. Loaned match tools follow the Norma level

Lv1 stone, Lv2 iron, Lv4 diamond. The loaned tool is swapped in place on `NormaLevelUp`. The ladder is `match_tool_tiers` in
`content/balance/starter-grant.yaml`; the match shop tools tab is removed. (Implemented by the norma-tools item agent; this ADR records the
decision.)

### 2. Annihilation runs five clock-driven phases

The phase clock is the only driver (no early cascade):

| # | phase | length | rule |
|---|---|---|---|
| 1 | preparation (`build`) | 6 min | pillars immune; base ores open, Mid outer ring and core closed |
| 2 | engage | 8 min | tier 1 pillars vulnerable; Mid outer ring (gold, diamond) opens |
| 3 | center | 8 min | tier 2 and 3 pillars vulnerable; Mid core (emerald) opens |
| 4 | escalation | 6 min | pillar damage x1.5 |
| 5 | decisive (`sudden-death`) | up to 5 min | damage x2 and bleed; the HP tiebreak decides at expiry |

Details:

- Bleed (replaces the old DoT): every 30 s each vulnerable pillar loses 10% of its max HP, never below a floor of 10% of max HP, so the
  bleed alone can neither destroy a pillar nor end the match (about 9 bleeds to the floor). The bleed ignores the damage multiplier.
  Tunables `bleed_interval_ticks`, `bleed_percent`, `bleed_floor_percent`, `*_damage_mult` and `*_phase_ticks` live in
  `content/game-modes/annihilation.yaml`; the overlay is strict (the removed `tier3_phase_ticks` / `sudden_death_dot_per_tick` are rejected).
- Mid ore gating: an ore-nodes file may declare a top-level `phase_gates` map (block id -> first phase). It is strict (every block of the
  file must be mapped, phases 1..5). The check lives in `OreNodeService` (via the `IMatchPhaseClock` port), not in the Paper listener; a refused
  hit posts `astera.annihilation.ore.phase-locked` ("opens in phase N (in m:ss)") and the deny sound. Files without `phase_gates`, and matches
  without a phase clock (every non-Annihilation mode), are not gated.
- Announcement: every phase change sends title, subtitle and sound to all participants (presentation moments `annihilation-phase`,
  `annihilation-final`), the objective boss bar carries the phase name and the time to the next phase (progress = remaining fraction), and the
  sidebar phase line has a countdown (the last phase counts down to the match end).
- Navigation: `AnnihilationClarity` gives each player one next-objective line about every 3 s, and the compass target follows the same choice:
  defend an own pillar hit in the last 10 s, else mine while preparing or while the Norma level is low (target = the team's own lowest standing
  pillar area as the proxy for its ore), else attack the weakest enemy vulnerable pillar. Without the compass in hand the line is text only.
- Query surface: the core event `AnnihilationPhaseTransitioned(matchId, oldPhase, newPhase, atTick)` is still published on each change, and
  `IMatchPhaseClock` (`current(matchId): MatchPhaseSnapshot?`, `ticksUntil(matchId, phase): Long?`) is registered in the `ServiceRegistry`.
- `AnnihilationWinConditionListener` reads the phase-5 length from the match's own config instead of the global default.

## Consequences

### Positive
- A player always sees which phase it is, what opens next and what to do now; the Mid becomes a timed objective instead of a free-for-all.
- Tools and Norma level tell one progression story.
- One clock query serves HUD, ore gate and navigation.

### Negative / Trade-offs
- Matches are longer (up to 33 min before the tiebreak); pacing values are balance guesses and need founder-check.
- "Own ore area" is approximated by the home pillar position until ore-node positions are exposed to the clarity layer.

### Mitigations
- Every length and multiplier is a yaml tunable; `AnnihilationPhaseFlowScenarioTest` pins the behaviour with a short clock.

## Alternatives Considered

- **Early cascade (tier 2 opens when tier 1 falls)**: kept only inside `AnnihilationMatchState.applyDamage`; the clock stays the sole phase driver.
- **Gate in the Paper ore listener**: rejected, the rule belongs to the ore service so game-sim sees it.
- **Keep the sudden-death DoT**: rejected, a flat per-tick DoT could end the match by itself and gave no tension curve.

## References

- 関連 ADR: [[adr/0108-annihilation-mode-foundation]]
- 関連 docs: [[vision/areas/modes-combat]]
