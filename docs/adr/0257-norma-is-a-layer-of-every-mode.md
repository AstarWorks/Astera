---
status: Accepted
date: 2026-10-26
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0257: Norma is a layer of every game mode, not a mode

## Status

Accepted. Builds on ADR-0262 (stars and Norma levels) and ADR-0263 (the top Norma level wins). Where ADR-0263 says "a
Norma match" read "a match with the Norma layer on", which is every mode except PvE waves.

## Context

Founder 2026-10-26: "ノルマはノルマというゲームモードではなく、全てのゲームモードに標準である要素であるだけ". Until now
the `norma` game mode owned the config (`content/game-modes/norma.yaml`), the queue lane, the sidebar goal line and the
fallback for every unknown mode key, so stars and Norma levels felt like one mode's rules.

## Decision

- **The layer** (stars from stage generators, kill bursts of carried stars, per-level STAR / KILL goal chosen in the
  picker, level-up presentation, the Norma sidebar footer) runs in every game mode. Its defaults live in
  `content/norma-layer/layer.yaml`; a mode overrides any key in its own `default_config`
  (`NormaLayerContents.configFor`: layer defaults, then the mode on top). `norma_layer: "false"` takes a mode out.
- **PvE waves opt out** (`pve-waves.yaml`: `norma_layer: "false"`): co-op against mobs has no player-vs-player Norma race
  and mob kills are not `PlayerKilled`. Switching it on is one line.
- **Win rule `norma_win` (default `true`):** a member reaching `norma_max_level` wins the match next to the mode's own
  objective, whichever happens first. `false` keeps the levels (HUD, presentation, ranking) but the mode's own objective
  is the only win path.
- **Time limit** (`time_limit_seconds`, ACTIVE time; `0` = no layer limit): the teams are ranked by the mode's own
  objective standing first (`IGameMode.objectiveStanding`, `null` when the mode has none), then the highest Norma level,
  then the stars carried, then the kills; a full tie is a draw. Defaults: skirmish and battle-loyal 600 s, the siege
  objective modes 1200 s, annihilation `0` (its own phase clock and sudden death).
- **Objective standing per mode:** battle-loyal kills, break-core alive (1) vs eliminated (0), carry-tnt deliveries,
  capture-wool captured zones, steal-jewelry banked jewels. Skirmish and annihilation report none.
- **The "norma" mode becomes `skirmish`** (`SkirmishGameMode`, lane `skirmish`): a plain team deathmatch whose only win
  path is the layer. `MatchmakingMode.Norma` and the `norma` queue lane are gone (`MatchmakingMode.SKIRMISH` is the
  default lane); a match without a mode key plays skirmish.
- Kills for the time-limit tie-break are tallied per player in `NormaProgress.totalKills`; the layer no longer writes
  `Match.score`, which belongs to the mode's own objective (battle-loyal) or display (skirmish).
- Every stage has star generators: `siege-isles` and `annihilation-arena` gained generator landmarks and
  `content/generators/{siege,annihilation}-*.yaml` (`ShippedArenaStagesBuildTest` requires a landmark per generator).

## Consequences

- One config surface for the layer; modes only state what differs.
- e2e tuning patches `norma-layer/layer.yaml` (levels, goals, limit) and every generator file.
- Weapon limits stay a skirmish-only setting (`weapon_limits` in `skirmish.yaml`); they are not part of the layer.
