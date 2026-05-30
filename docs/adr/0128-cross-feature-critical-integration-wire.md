---
status: Accepted
date: 2026-05-30
deciders: orchestrator (Wave K 5integration-wire)
supersedes: null
superseded_by: null
---

# ADR-0128: Cross-feature critical integration wire — combo / focus-fire / target-lock damage bonuses + tutorial→achievement / quest→achievement / achievement→ProfileCard / match→engagement chains

## Status

Accepted

## Context

Round 13 closed Wave J (server-ops + news/changelog/login-bonus content
scaffold). A follow-up cross-feature audit surfaced **seven Annihilation-
mode-style silent drops** — events that fire but have zero subscribers,
ports that hold runtime state but are never read at decision time:

1. **`ComboRouteCompleted` published, subscriber count zero.** The
   `IComboTracker` correctly published the route + reset the player's
   state, but no one consumed `bonusDamageMultiplier`. Combo finishers
   visually played; the follow-up strike dealt base damage.
2. **`FocusFireState.bonusMultiplierFor` never called by the damage
   pipeline.** The tracker accumulated focus windows correctly, the HUD
   chip lit, but `ApplyDamageUseCase` never consulted the bonus.
3. **`ITargetLockController.consumeBonusFor` never called by the damage
   pipeline.** Same pattern — lock acquisition fired the HUD event but
   the ranged hit did not amplify.
4. **`TutorialCompleted` published, subscriber count zero.** The
   onboarding loop had no closing milestone — no achievement, no
   reward — so players couldn't see "I'm done with the tutorial" in
   their badge wall or wallet.
5. **`QuestStageCompleted` did not feed achievement progress.** Quest
   chains had a `rewards` list (currency / title / item) but no path to
   say "this stage also counts towards <achievement>". The cleanest
   shape (a dedicated `linked_achievement_id` field) didn't exist yet.
6. **`AchievementUnlocked` did not refresh the long-lived
   `PublicProfileCard`.** Wave 5flex already had
   `RefreshNameplateOnAchievementUnlockedListener` for the in-match
   nameplate cache, but the public profile card (`/profile <name>`,
   web roster, Discord embed) stayed stale until a manual
   `RecomputeProfileCardUseCase.recompute(...)` call from elsewhere.
7. **`MatchEnded` did not refresh per-player engagement snapshots.**
   `EngagementDashboardService` recomputed on a 5-minute scheduler;
   a player finishing a match would not see their score bump until
   the next tick — the freshest signal in the player's session was
   the most stale on the dashboard.

The pattern in all seven is identical to Wave I's Annihilation match
auto-end bug (ADR-0126): a publisher exists, a consumer is missing,
the build still passes (Konsist doesn't catch silent drops), and the
player-visible behaviour is "the feature isn't there". Each individual
miss has a working PR fix; the seven together are one wave because
the audit is a single pass and the wires share constructional
patterns (event subscribe → use case invoke → log).

## Decision

Wire all seven with a single new plumbing port + six new listeners +
two domain extensions, no behavioural changes outside the failing path:

### A/B/C. Damage-bonus integration (combat)

Introduce a new outbound port [IDamageBonusSource] in
`combat.application.port.outbound`:

```kotlin
public fun interface IDamageBonusSource {
    public fun bonusFor(attempt: DamageAttempt, nowTick: Long): Double
}
```

[ApplyDamageUseCase] gains an optional `bonusSources: List<IDamageBonusSource>`
constructor parameter (default `emptyList()` to keep existing tests
green). After `damageRule.resolve(...)` returns, the use case multiplies
the result by the product of `bonusSources.map { it.bonusFor(attempt,
nowTick) }` and publishes the boosted [ResolvedDamage] through
`DamageDealt`. Multiplication folds **after** armor resolution — a bonus
should never turn a fully-absorbed hit into a chip hit.

Three impls land in `combat.application.service.damagebonus/`:

- [ComboBonusSource]: subscribes to `ComboRouteCompleted`, stores
  `pending[playerId] = bonusDamageMultiplier`, consumes on the player's
  next attempt regardless of weapon (the chain was already
  weapon-gated when scored). One-shot.
- [FocusFireBonusSource]: delegates to
  `IFocusFireTracker.bonusMultiplierFor(attacker, victim, atTick)`. Stateless.
- [TargetLockBonusSource]: ranged-only filter (`DamageSource.Projectile`),
  delegates to the new `PlayerId`-keyed
  `ITargetLockController.consumeBonusFor(attackerId, victimId, nowTick)`
  overload. Single-use per acquisition.

The new `consumeBonusFor(PlayerId, PlayerId, Long)` overload + an
`armAgainstPlayer(attacker, victim, atTick)` companion are added to
[ITargetLockController] so the player-keyed path doesn't require the
`EntityHandle` the use case can't access; the existing `EntityHandle`
overload is kept for Wave 6b's Paper adapter.

### D. Tutorial → Achievement + Reward (stats + economy)

Two new listeners, both subscribed to `core.domain.event.TutorialCompleted`:

- `:plugin:stats` / `application/eventbus/listeners/onboarding/`:
  [GrantAchievementOnTutorialCompletedListener] — calls
  `UnlockAchievementUseCase.unlock(playerId, AchievementId("tutorial-graduate"))`.
- `:plugin:economy` / `application/eventbus/listeners/onboarding/`:
  [GrantRewardOnTutorialCompletedListener] — calls
  `DepositUseCase.execute(playerId, Currency.STAR, 50, CurrencyChangeReason.TutorialReward)`.

A new sealed-interface variant `CurrencyChangeReason.TutorialReward`
joins the existing reasons so analytics can split tutorial-driven
deposits from match rewards. `content/advancements/tutorial-graduate.yaml`
+ matching i18n keys land alongside.

### E. Quest → Achievement progress (core + stats)

`QuestStage` gains an optional `linkedAchievementId: AchievementId?`
field (and `QuestStageDto` an `linked_achievement_id: String?` field).
Authors mark "this stage's completion contributes to <achievement>" in
YAML. A new listener in `:plugin:stats`
(`application/eventbus/listeners/quest/`):

[UpdateAchievementProgressOnQuestStageCompletedListener] resolves the
stage via `IQuestChainRegistry`, reads `linkedAchievementId`, and
unlocks the achievement (one-shot in Phase 1 — no multi-tick progress
counter exists yet). `content/quests/forest-exploration.yaml` is wired
to the new `explorer` achievement.

### F. AchievementUnlocked → ProfileCard recompute (stats)

A new listener in `:plugin:stats`
(`application/eventbus/listeners/flex/`):
[RecomputeProfileCardOnAchievementUnlockedListener] calls
`RecomputeProfileCardUseCase.recompute(playerId)`. Independent from
Wave 5flex's nameplate listener — different cache, different
invalidation cadence; both subscribe to `AchievementUnlocked` in
parallel.

### G. MatchEnded → Engagement snapshot recompute (core + match)

`EngagementDashboardService` gains a new
`recomputeForPlayer(playerId)` method — re-stamps the player's
existing snapshot's `lastSeenAt = now` + recomputes the score. No-op
if the player has no prior snapshot.

A new listener in `:plugin:match`
(`application/eventbus/listeners/engagement/`):
[RecomputeEngagementSnapshotOnMatchEndedListener] resolves the match
via `IMatchRepository`, walks every team, and calls
`recomputeForPlayer` for each participant.

## Consequences

### Positive

- **All three combat-depth surfaces are now end-to-end live.** Players
  who chain finishers, focus-fire as a team, or lock-on with a bow
  see their damage actually amplify — the gameplay surfaces are no
  longer cosmetic.
- **Onboarding loop has a closing milestone.** A graduate sees their
  achievement badge + STAR credit immediately on tutorial completion.
- **Quest authors get a clean "this stage unlocks X" knob.** No need
  to misuse the `rewards` list (which is for grants, not unlocks).
- **PublicProfileCard stays current.** Web / Discord readers see the
  unlock as soon as it lands, not on the next compute cycle.
- **Engagement dashboard freshness gap closes.** Per-player score
  refresh on every match end means DAU/WAU/MAU sees the freshest
  signal in the player's session immediately.

### Negative / Trade-offs

- **`ApplyDamageUseCase` is now configurable with `IDamageBonusSource`s.**
  The default empty list keeps existing tests green, but the new
  contract is one more thing the use case can be misconfigured against
  (e.g. a poorly-behaved source returning `NaN`). The use case asserts
  finite + non-negative on each return to fail loud.
- **Combo bonus is consume-on-any-weapon.** The pending bonus survives
  weapon switches between chain-fire and the follow-up strike. We
  judged this preferable to silently swallowing the bonus if the
  player draws their finisher item between the chain landing and the
  strike (a UX trap for ranged finishers).
- **`linkedAchievementId` is unlock-on-stage-completion (binary), not
  progress-tick.** Phase 1 PoC has no multi-tick achievement counter;
  the field name keeps the path open for a counter-based model later
  without renaming the YAML key.
- **`recomputeForPlayer` only re-stamps `lastSeenAt`.** Pulling the
  underlying inputs (playtime / streak / skills) from raw repos at
  MatchEnded would require new ports per input; the listener's job
  is "this player was active right now", which `lastSeenAt = now` +
  score recompute captures sufficiently.

### Mitigations

- The bonus-source order in `AsteraCombatPlugin` is fixed (combo →
  focus → target-lock). The orchestrator owns the wiring so the
  consume race is deterministic.
- `IDamageBonusSource` impls live next to their tracker in
  `combat.application.service.damagebonus/` — same package depth as
  the existing service folders, easy to audit at review time.
- The seven new listener call sites each log at INFO on success +
  DEBUG/WARN on the expected failure modes — when something stops
  working, the log line tells the operator which wire failed.

## Alternatives Considered

- **Fold the three damage bonuses into `DamageRule.modifiers`.**
  Rejected because the modifiers are pure stateless transforms over
  `WorkingDamage`; the bonus sources consume time-windowed runtime
  state and may mutate on read (combo one-shot, target-lock single-use).
  Keeping them outside the rule preserves `DamageRule.resolve` as a
  pure function of `(attempt, armor, container, team)` and lets
  Wave K wire each tracker independently. Tests that don't care about
  bonuses don't have to pre-stub them away.
- **Put the tutorial listeners in `:plugin:core`.** Rejected per
  ADR-0022 sibling isolation — core can't import
  `UnlockAchievementUseCase` (`:plugin:stats`) or `DepositUseCase`
  (`:plugin:economy`). The listeners belong on the *invoke* side, not
  the *event-source* side.
- **Widen `MatchEnded` to carry the participant roster.** Rejected
  because most subscribers don't need it; resolving via
  `IMatchRepository` mirrors `RecordMatchPlayedOnMatchEndedListener`'s
  established pattern.
- **Make achievement progress an `IAchievementService.incrementProgress`
  multi-tick counter and have the quest listener call that.** Rejected
  for Phase 1 PoC — no such service exists and adding it would expand
  scope into a counter persistence + recompute pipeline. The
  unlock-on-stage-completion shape ships now; the counter is a
  future supersede.
- **Have `ComboBonusSource` re-check the weapon at consume time.**
  Rejected; the tracker already guarantees the chain was weapon-bound
  when scored, and re-checking would silently swallow the bonus if
  the player drew their finisher item between scoring and the strike
  (UX trap for ranged finishers).

## References

- 関連 ADR:
  [[adr/0105]] (Wave 5combat-depth — Combat Depth Foundation: combo /
  focus-fire / target-lock trackers),
  [[adr/0102]] (Wave 5flex — ProfileCard surface),
  [[adr/0104]] (Wave 5engage — EngagementDashboardService),
  [[adr/0091]] (Wave 5s — Quest chains),
  [[adr/0125]] (Wave H 5content-depth — content baseline + 3 listener wires),
  [[adr/0126]] (Wave I 5match-wireup-fix — 4 critical match listener
   subscribe fix; same silent-drop pattern as Wave K's seven),
  [[adr/0022]] (multi-plugin sibling isolation matrix)
- 関連 docs: [[architecture/principles.md]]
- Wave handoff: ../IMPLEMENTATION-ROADMAP.md §Round 14
