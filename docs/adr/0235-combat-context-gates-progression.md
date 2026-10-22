---
status: Accepted
date: 2026-10-23
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0235: Combat context gates progression, economy and kill credit

## Status

Accepted. Addendum to ADR-0210 (progression loop wiring) and ADR-0200 (lobby training range).

## Context

Founder report: STAR rewards and kill credit that should only happen in PvP fired on the lobby
training dummies. Audit of every subscriber of `DamageDealt` / `PlayerKilled` / `MobKilled`:

- A hit on a dummy goes `ApplyDamageUseCase.executeOnEntity` -> `TrainingDummyEntityDamageGateway` and
  publishes a normal `DamageDealt(attacker = player)`. Nothing on the event said "dummy", so
  every damage subscriber treated it like PvP: `damage` missions, `damage-1k/10k` achievements,
  the `damageDealt` stat, Combat skill XP (1 XP per HP) and the Combat skill level-ups.
- `DepositOnXpListener` turned every non-match `XpGranted` into STAR (`max(1, xp / 10)`): kill XP
  (100 -> 10 STAR per kill, on top of the match table's 5), mission XP and level-up XP paid STAR.
  `match-rewards.yaml` already states "an XP grant never converts to STAR on top";
  `docs/game-design/economy/README.md` lists the STAR sources (match table, first grant) and none of
  them is XP.
- Lobby mobs: `MobKilled` (Bukkit death) fed `kill_count` quests and BossSlayer XP for any killer, anywhere.
- Players fighting in the lobby outside the training range: `PlayerKilled` fed kill XP, missions, kill
  achievements, quests, stats and assists with no match.

## Decision

**Every combat event carries a `CombatContext`, set where the event is created; only `Match` counts.**

`core.domain.combat.CombatContext`: `Match(matchId)` | `Training` | `Lobby` | `World`, with
`countsForProgression = this is Match`. Added (default `Match()`, a test convenience; every production
source sets it) to `DamageDealt`, `PlayerKilled`, `MobKilled`.

Producers (`CombatContextResolver`, looks up `IMatchTeamLookup` per call because Match registers it after Combat):

| Source | Context |
|---|---|
| `ApplyDamageUseCase` player path | `Match` when the victim and (if a player) the attacker are in a match, else `Lobby` |
| `ApplyDamageUseCase` entity path | `Training` for a dummy (`IEntityDamageGateway.isTrainingTarget`), `Match` when the attacker is a participant, else `World` |
| `VanillaKillResolver` (fall, DoT, vanilla melee) | as the player path |
| `BukkitEventAdapter` `MobKilled` | `Match` for a participating killer, else `World` |

Consumers: progression, economy, stats and kill credit return early unless `context.countsForProgression`:
`RewardOnKill` (kills stat + kill XP), `RewardOnDamage` (damage stats), `MissionProgressOnKill`,
`TrackNormaProgress` (damage), `UnlockAchievementOn{Kill,Damage}Milestone`, `AwardCombatXpOnDamageDealt`,
`AwardBossSlayerXpOnMobKilled`, `TrackQuestProgress` (kills, mob kills), `TrackAdvancementProgress` (kills),
`RecordDamageHistory` + `AssistAttribution` (assists), and the kill feed.

Kept on purpose for every context: `HitFeedbackListener` (hit sound, floating number), the dummy's own
HP/DPS label, ego gauge gain (practice on a dummy is fine), immunity checks.

Non-match mob kills give **nothing** for now (no XP, quest progress, loot credit). PvE credit later is a
deliberate new rule: add a context variant (or a PvE mode on `Match`) and opt consumers in.

**Economy**: XP never converts to STAR. `DepositOnXpListener` is removed. STAR comes from the match-end
table (`match-rewards.yaml`, which has its own per-kill STAR), mission / quest / achievement currency
rewards and the login bonus - the sources `economy/README.md` lists. XP stays a level concern.

## Consequences

- Sandbag hits and lobby brawls leave no trace in missions, quests, achievements, stats, XP, STAR or the kill feed.
- Kill XP in a match (100 per kill) still stacks with the match table's per-kill XP (20); tuning that is a
  separate balance question.
- ADR-0210's note "revisit the XP -> STAR conversion" is resolved: the conversion is gone.
- `Match(matchId)` has a null id only for lookups that cannot name the match (the game-sim lambda lookup).
- `ApplyDamageUseCase`'s own `teamLookup` (friendly fire) is still the wiring-time value; only the new
  context resolver is lazy. Fixing friendly-fire timing is out of scope.
