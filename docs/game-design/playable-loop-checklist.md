# Playable loop checklist (G1)

Status: suites and fixes are written but **not yet run** (no local gradle/e2e, no CI by instruction). Every row marked
"unverified" must be confirmed by the first main CI e2e run; failures there are the next work list.

## The loop

queue join -> match found -> countdown -> ACTIVE -> fight (aim, shoot, reload) -> death -> respawn -> score/objective
progress on the sidebar -> win condition ends the match (never an admin) -> summary + rewards -> back in the lobby
(kit in slot 8, lobby sidebar, playable game mode, no match left listed).

## Verified by which suite

| Step | Norma 2v2 (`NormaPlaythroughE2eTest`) | Annihilation (`AnnihilationPlaythroughE2eTest`) | Break-core (`SiegePlaythroughE2eTest`) |
|---|---|---|---|
| Queue -> ACTIVE pacing | yes | yes | yes |
| Teams / spawns | 2v2 by side | 3 teams, 1 bot each | 2 sides |
| Sidebar replaces lobby, shows state | scores | phase + pillar tiers/HP | cores intact |
| Pistol aim/shoot/reload | yes (5 duels) | shots at pillars | - |
| Death -> spectator -> respawn time | yes | elimination spectates | - |
| Score on sidebar follows kills | yes | pillar HP | core down |
| Build-phase immunity | - | yes | - |
| Damage by skill and by block break | - | yes (survival dig, creative fallback flagged) | yes (enemy core; own core refuses) |
| Win condition ends the match | 3 kills (e2e) | last team standing | last core |
| Summary, score line, own stats | yes | yes | yes |
| Rewards announced | yes | yes | yes |
| Back in lobby, no stuck state | yes | yes (spectators too) | yes |
| No server errors in log | yes | yes | yes |

Timers are shortened only in the e2e image (`docker/e2e-tune-content.sh`, build arg `E2E_FAST=1`); the script fails the
build if a patched key disappears from content.

## Problems found by reading the code, and fixes

1. Norma had no end: queue-formed Norma matches never ended. Fix: a Norma match is won by reaching the top Norma level
   (`NormaLevelService`); `NormaTimeLimitListener` counts kills and ends it after `time_limit_seconds` (highest level, then
   stars carried, then kills; ADR-0252). Keys in `content/game-modes/norma.yaml`.
2. Sudden death expiry only flipped the phase to ENDED and never published `MatchEnded` (no result, rewards, return).
   Fix: `PhaseTimerUseCase.onSuddenDeathExpired`, wired to `EndMatchUseCase(SuddenDeathResolved)`. Unit-tested.

## Known gaps

- Norma sidebar shows scores without the goal ("Red: 2" not "2/10"); end reason prints "objective completion" for
  Norma wins (TeamVictory is mapped to that text).
- Norma stars and levels are in (docs/game-design/stars-and-norma.md, ADR-0251): generators pop star drops, a kill bursts the victim's stars, norma levels (star or kill goal) are the match win path next to the kill score. Stars still do not add to the kill score; spending stars (items, ultimates) is not built yet.
- A kill that ends the match may race the death/respawn coordinator for the last victim (victim is returned to the
  lobby by match end; unverified).
- Pistol bullets hitting pillars, survival block-break speed on pillars/cores, and the shared arena between lanes are
  unverified until the first CI run.
- Bots are positioned with RCON `tp` (flat arena, static targets); movement/pathing is not exercised.
