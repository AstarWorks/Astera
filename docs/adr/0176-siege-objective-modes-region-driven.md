---
status: Accepted
date: 2026-10-22
deciders: founder + Claude (WP11)
supersedes: null
superseded_by: null
---

# ADR-0176: Siege (objective) modes are region-driven per-match `IGameMode`s

## Status

Accepted

## Context

WP11 played the five legacy "Wave 5c" modes (BreakCore, CarryTNT, CaptureWool,
StealJewelry, BattleLoyal) in the headless simulator (ADR-0174) for the first
time. None of them could win a match:

- Only `BreakCoreListener` was subscribed; the other four listeners were never
  wired, and `IGameMode` impls were event-less façades.
- Every listener was global (no per-match mode filter) and BreakCore published
  `winningTeam = null`; the match end API could not carry a winner at all.
- The listeners keyed on item pickups of `astera:` items that no content
  defines, on core-block ids instead of positions, and on region names
  (`red_base`, `wool_a`) no stage contained.
- `Stage` dropped region names at load time; nothing in production published
  `PlayerMoved` or `BlockBroken`; the core `IStageRegionIndex` that should turn
  moves into region transitions was never filled.
- CaptureWool counted `ticksToCapture = 600` once per *second* (10 minutes).

## Decision

1. **Objectives live in the stage as named regions** (`core-<team>`,
   `base-<team>`, `jewelry-<team>`, `tnt-spawn`, `wool-*`); `core.domain.stage.Region`
   carries its YAML `name`. No item content is needed: picking up TNT / a jewel
   is *entering a zone*, delivering is entering a base zone.
2. **One `IGameMode` per mode owns its per-match state** (`SiegeGameMode<R>`
   base in `match/application/gamemode/siege/`): it reacts only to events of
   its own matches (`EntityEnteredRegion/LeftRegion`, `BlockBroken`,
   `PlayerKilled`, a 1 Hz tick), declares the winner through the new
   `EndReason.TeamVictory(winner)` (WP10 mechanism), and places / removes its marker blocks via
   `IWorldGateway`. Tunables come from `content/game-modes/<id>.yaml`
   `default_config`.
3. **Region transitions are derived from the match's own stage**
   (`MatchRegionTransitionListener`, stateless `from`/`to` diff over
   `PlayerMoved`), replacing the relay over the unused core region index.
4. **Paper producers**: `PaperMatchWorldInputAdapter` publishes `BlockBroken`
   and (block-throttled) `PlayerMoved` for players in a match.
5. **Deleted**: the legacy mode listeners, the `BreakCore/CarryTNT/CaptureWool/
   StealJewelry/BattleLoyal` config data classes, the legacy `GameModeContext`
   and `IGameModeRegistry` (nothing read them), `RegionTransitionRelayListener`.
   `carry-mini-tnt` keeps its façade + legacy listener; it is not wired to an
   objective yet.

## Consequences

- All five modes win in `:tools:game-sim` (`SiegeModesScenarioTest`) and have a
  queue lane on the shared `siege-flat` stage (one siege match at a time).
- A new objective mode is: one `SiegeGameMode` subclass + a game-mode YAML +
  stage regions + a lane.
- Known gaps found on the way and left to their owners: status effects survive
  a respawn, and a DoT kill carries no origin player (no kill credit).
