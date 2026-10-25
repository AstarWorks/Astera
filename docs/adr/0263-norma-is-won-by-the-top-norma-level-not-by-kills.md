---
status: Accepted
date: 2026-10-25
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0263: A Norma match is won by the top Norma level, not by a kill count

## Status

Accepted. Supersedes, for the Norma mode only, the "first team to N kills wins" rule recorded in ADR-0027 (score
threshold 10), ADR-0117 Axis 5 (`NormaVictoryConditionEvaluatorListener`) and the Norma HUD / `EndReason.NormaReached`
parts of ADR-0244. Those ADRs stay immutable; this one wins on conflict.

## Context

ADR-0262 introduced 100% Orange Juice style Norma levels (STAR or KILL goal per level). The older G1 rule survived next
to them: every enemy kill scored a team point and the first team to `score_threshold` (10) kills ended the match with
`EndReason.NormaReached`. Live logs showed matches ending that way, so the star/level game never decided anything.
A second legacy path, `NormaVictoryConditionEvaluatorListener` (`VictoryConditionEvaluator`, threshold 10 on
`Match.score`), could also push the phase on star pickups.

## Decision

- The only way to win before the clock runs out is a member of a team reaching `norma_max_level`
  (`EndReason.NormaLevelReached`, ended by `NormaLevelService`).
- Removed outright: `score_threshold` (norma.yaml and the e2e tuning), the kill-count end in the old
  `NormaKillScoringListener`, `EndReason.NormaReached`, its i18n key, and `NormaVictoryConditionEvaluatorListener`.
- Kills still count: they feed a player's KILL goal and, per team, `Match.score` (result screen and last tie-break).
  The listener is renamed `NormaTimeLimitListener`.
- Time limit (`time_limit_seconds`): highest member Norma level wins; tie: most stars carried by the team; tie: most
  kills; still tied: draw (`EndReason.NormaTimeLimit(null)`).
- Sidebar: the goal line names the top level to reach (`astera.hud.sidebar.norma.goal`, `{max}`); team lines show kills.
- `VictoryConditionEvaluator` / `PhaseTimings.SCORE_THRESHOLD` stay for the generic phase machine; Norma no longer feeds them
  a win.

## Consequences

- Game-sim: `StarsNormaScenarioTest` (time-limit tie-breaks), `MidMatchReconnectScenarioTest` (20 kills do not end a match,
  the top level does). E2E: `NormaPlaythroughE2eTest` wins through KILL goals up to level 3; `BotVsBotE2eTest` and
  `StarsE2eTest` already assert the level / time-limit reasons.
- Pace is tuned in content only (norma levels, generator `period_seconds` / `amount`); see `docs/game-design/stars-and-norma.md`.
