---
status: Accepted
date: 2026-10-27
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0288: Norma levels unlock ultimates and special items; they never end a match

## Status

Accepted. Supersedes the win parts of ADR-0268 (`norma_win`, "Norma wins next to the objective"), ADR-0271 (the top level as
a goal to win) and ADR-0272 (a match is won by the top Norma level). Those ADRs stay immutable apart from their Status lines. The "Level gating" decision (per-binding `min_norma_level`, s3 at Lv 2, ego at Lv 3) is superseded by ADR-0289 (Norma levels unlock weapon rarity).

## Context

ADR-0272 made the top Norma level the way to win. With the siege objective modes (carry-tnt, steal-jewelry, capture-wool,
...) as the real game, a star race that ends the match competes with the objective and confuses what players are fighting for.
The founder chose the RTM-era reading: Norma level is in-match power progression.

## Decision

- No Norma level ends a match. `EndReason.NormaLevelReached` and the `norma_win` layer key are removed. Only a mode's own
  objective, or the time limit, ends a match.
- Time-limit ranking: the mode objective, then stars carried (team sum), then kills, then draw. Norma level is not ranked.
- Level gating: an item skill binding may carry `min_norma_level` (default 1). Shipped: `*-s3` (sneak_right) bindings need
  Lv 2, `*-ego` (drop) ultimates need Lv 3. Core owns the port `IMatchLevelGate`; Match implements it and Items consults it
  lazily through the `ServiceRegistry` (ADR-0022). No registration, or no active match, means no gate.
- Feedback: a refused cast plays the `norma-locked` presentation (deny sound + action bar "unlocks at Lv N"); the item tooltip
  shows a greyed locked line per gated binding; a level-up names what it unlocked (subtitle + private chat line); the sidebar
  shows the next unlock instead of a rival-level race.
- Stars remain match-scoped and are never a win reward (ADR-0271).

## Consequences

- Game-sim: `NormaLayerScenarioTest` and `MidMatchReconnectScenarioTest` assert the top level leaves the match ACTIVE;
  `NormaExplainedScenarioTest` checks briefing/result wording; `NormaUnlockScenarioTest` covers a locked ultimate refused
  at Lv 1/2 and cast after the level-up.
- Higher gate tiers (Lv 4/5) and special pop-up items are not designed yet; add them as `min_norma_level` values in content.
