---
status: Accepted
date: 2026-10-22
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0210: Progression loop wiring (achievements, missions, quests, daily bonus)

## Status

Accepted. Applies the ADR-0195 overlay pattern to the progression state and connects the
progression systems to real events.

## Context

An audit of the progression domain found systems that existed but never ran:

- The achievement catalog was never filled (`registerAll` had no caller), so every
  `UnlockAchievementUseCase` call returned `NotInCatalog`: no achievement could unlock.
- No code called `IQuestService.startChain` / `progressCurrent`: quests never started or advanced.
- Missions (`content/norma`) only counted kills, never reset daily / weekly, had one example file,
  and a completed mission's reward bundle was enqueued on `IRewardBridge`, which nothing consumes
  (its XP was never paid).
- `ILoginBonusService.claimDaily` returned `NotImplemented`.
- Postgres classes for skill progression, quest chains, advancements and the engagement snapshot
  existed but were never staged by `AsteraPersistencePlugin`; achievements and streaks had no table.

## Decision

1. **Persistence** (`AsteraPersistencePlugin.stageAll`): stage the four existing repositories; add
   V60 `player_achievements`, V61 `login_streaks`, V62 `norma_progress.period_key` with their
   Postgres repositories. Consumers keep the `get() ?: InMemory()` idiom. Migration numbers start at
   60 to stay clear of parallel branches.
2. **Achievements**: `content/achievements/*.yaml` is the catalog (`AchievementContents`, hot
   reloadable; the repository catalog is re-synced on reload). New unlock sources: 7-day login streak,
   level 5 / 10, first mission.
3. **Missions**: `MissionProgress.periodKey` (day / ISO week; seasonal never resets) makes daily and
   weekly rows restart; a stale row counts as 0. `damage`, `match_wins` and `xp_gain` targets are fed by
   `TrackNormaProgressListener` (mission/admin XP is excluded from `xp_gain`). `PayOutMissionCompletedListener`
   grants the XP and announces completion (the currency is still paid by economy's
   `DepositOnMissionCompletedListener`); the dead bundle enqueue was removed.
4. **Quests**: every chain is started on join; `TrackQuestProgressListener` advances the current stage
   from kills (player and mob), won matches, earned currency and acquired items; stage clears are announced.
5. **Daily login bonus**: `DefaultLoginBonusService.claimDaily` (streak continues only when the last
   claim was yesterday, rotation wraps, claims serialised per player, `CurrencyChanged(LoginBonus)` +
   `LoginBonusClaimed`). On a lobby backend the claim runs 100 ticks after join. `items` in a reward are
   carried but not delivered (core has no inventory port); the shipped table is currency-only.
6. **Screens**: `/astera daily|missions|quests|achievements`.

## Consequences

- Rewards stack with the existing `DepositOnXpListener` (XP -> STAR); the economy owner may want to
  revisit that conversion now that missions and the login loop also grant XP.
- "Today" is the server's local calendar day; multi-region servers would need a configured zone.
