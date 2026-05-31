---
status: Accepted
date: 2026-05-31
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0141: Match enforcement — verification record + Match Pause Phase 2 deferral

## Status

Accepted

## Context

Round 18 audit α (Explore agent #2) claimed 4 in-match enforcement gaps
required closure before Wave 6:

1. Spawn protection enforcement — claimed missing
2. Friendly-fire enforcement — claimed missing
3. AFK detection during match — claimed missing
4. Match pause coordination — claimed missing

This is a **verification-record wave**. Phase A grep against the codebase
showed **3 of 4 claims are FALSE alarms** — the audit agent did not
locate existing wiring. The 1 remaining true gap (Match Pause) requires
multi-mode coordination design that exceeds Round 18's "pre-Wave-6 gap
closure" scope and is intentionally deferred to Phase 2.

This ADR records the verifications + the deferral decision so future
audits do not re-flag the same false alarms.

### Phase A verification matrix

#### ✅ §A — Spawn protection enforcement: ALREADY WIRED

- `ApplyDamageUseCase.execute()` line 86-89 (`plugin/combat/.../usecase/damage/ApplyDamageUseCase.kt`):
  ```kotlin
  if (statusContainer.has(attempt.targetId, IMMUNITY_ID)) {
      return Result.failure(ApplyDamageError.TargetImmune(attempt.targetId))
  }
  ```
- Triggered by `PlayerProtectionGranted` event setting an `IMMUNITY_ID`
  status effect on the player; cleared by `PlayerProtectionExpired`
  after the configured window. Damage during the window short-circuits
  before reaching the damage gateway.
- Verdict: **TRUE alarm = false**. Spawn protection enforces correctly
  at the use-case boundary.

#### ✅ §B — Friendly-fire enforcement: ALREADY WIRED

- `ApplyDamageUseCase.execute()` lines 92-94 + 107+:
  ```kotlin
  val attackerTeam = attackerId?.let { teamLookup?.teamOf(it) }
  val defenderTeam = teamLookup?.teamOf(attempt.targetId)
  // ... DamageRule.resolve(... attackerTeam, defenderTeam ...)
  if (resolution.resolved.wasBlocked) {
      eventBus.publish(FriendlyFireBlocked(...))
  }
  ```
- `IMatchTeamLookup` port resolves both attacker + defender team;
  `DamageRule` applies the `FriendlyFirePolicy.BlockSameTeam` policy
  (configured at `AsteraCombatPlugin.kt:292`); blocked hits publish
  `FriendlyFireBlocked` and return without damage application.
- Verdict: **TRUE alarm = false**. Friendly-fire enforces correctly at
  the use-case boundary.

#### ✅ §C — AFK detection during match: ALREADY WIRED

- `InMemoryAfkDetector` registered at `AsteraCorePlugin.onEnable:1484`.
- `KickPlayerOnAfkKickedListener` wired at `AsteraCorePlugin.onEnable:1970`
  — subscribes `PlayerAfkKicked` event and calls
  `IModerationService.applyKick(...)`.
- `AfkWarningHudListener` wired at the same location — surfaces the
  warning HUD before the kick fires.
- The detector reads from `IPlayerMovementGateway` / `IPlayerInputGateway`
  events (Wave 6a Paper-deferred per ADR-0072), so the Phase 1 PoC
  Wave 5q infrastructure is complete; the trigger source is what
  Wave 6a will wire in.
- Verdict: **TRUE alarm = false**. AFK kick path is fully wired; the
  per-tick input source ships with Wave 6a Paper adapter (already in
  ADR-0072 deferred allow-list).

#### ❌ §D — Match pause coordination: TRUE GAP (deferred to Phase 2)

- `MatchStateMachine.TransitionTrigger.AdminEnd` exists per
  `plugin/match/.../domain/match/MatchStateMachine.kt`; that transition
  marks the match as ended via admin command.
- However, there is **no intermediate "Paused" state** — match phases
  transition `Lobby → Running → Ended` without a Pause variant.
- Consequently: no `MatchPaused` / `MatchResumed` domain events; no
  `ISchedulerCoordinator` port; no listener pauses per-mode tick
  schedulers (anomaly, generator, respawn timer, sudden-death
  accelerator).
- Verdict: **TRUE gap**. Match pause is genuinely missing infrastructure.

### Why Match Pause is deferred to Phase 2

Match pause requires:

1. New `MatchStateMachine` state `Paused` + transition rules from
   `Running ↔ Paused` (only admin trigger allowed; no auto-transitions).
2. Domain events `MatchPaused(matchId, byAdmin, atTick)` +
   `MatchResumed(matchId, atTick, durationPausedTicks)`.
3. `ISchedulerCoordinator` port that game-mode schedulers consult on
   each tick: "is the match paused? if yes, skip work, hold timers".
4. Per-mode integration: each `IGameMode.onTick` must check pause
   state. Existing modes (Norma, Annihilation, BreakCore, CaptureWool,
   CarryTNT, CarryMiniTNT, BattleLoyal, StealJewelry) all need updates.
5. Anomaly scheduler + generator scheduler + respawn timer + sudden
   death accelerator: each maintains its own tick state and needs
   pause-aware wiring.
6. UI: match-paused HUD overlay (sidebar banner, action bar message).
7. Admin command: `/astera-match pause <matchId>` + `/astera-match
   resume <matchId>`.
8. Konsist rule: every game-mode `onTick` MUST check pause state
   (similar to ADR-0131 wiring leak prevention shape).

Total estimated effort: 1-2 full waves (8-15h), spanning Match + Combat
+ every game-mode plugin file. Exceeds Round 18 "pre-Wave-6 gap
closure" scope; properly belongs in **Phase 2 admin tooling expansion**
(parallel to Wave 8a per IMPLEMENTATION-ROADMAP).

**Acceptable Phase 1 PoC fallback**: admin uses
`MatchStateMachine.TransitionTrigger.AdminEnd` to forcibly end a match
(existing path). Pause-and-resume is a polish-tier feature for live-ops
control, not a Phase 1 PoC ship blocker.

## Decision

1. **Document the 3 false alarms** as verified-wired per §A / §B / §C
   so Round 19+ audits do not re-flag.
2. **Defer Match Pause coordination to Phase 2** per §D; no Round 18
   implementation.
3. **No code change** in this wave. ADR-only verification record.

## Consequences

### Positive

- Round 19+ audits have a documented reference for the 3 false-alarm
  enforcement areas — future agents reading this ADR will not re-flag.
- Match Pause Phase 2 scope is explicit + sized, so the Phase 2
  planning has a clean handoff point.
- Wave protocol momentum maintained — Round 18 ships 5 waves of real
  work (AC + AB + AE-verification + AG + AF) without a brittle "force
  scope" wave on Match Pause.

### Negative / Trade-offs

- Match Pause remains absent until Phase 2 admin tooling lands.
  Operators must use the heavier `AdminEnd` transition for incident
  response in Phase 1 PoC.
- ADR-only wave produces no code asset — the verification record is the
  asset. Some readers may expect a code commit per wave; the §Context
  + §Phase A verification matrix replaces it.

### Mitigations

- The 3 ADR §Phase A verification §A/§B/§C blocks each cite the exact
  `file:line` so a future contributor reading this ADR can grep-jump to
  the source.
- Phase 2 admin tooling wave will reference this ADR's §D as the
  authoritative scope estimate for Match Pause work.

## Alternatives Considered

- **Alt A: Implement Match Pause in Round 18**: rejected. Scope (8-15h)
  exceeds Round 18 wave budget; multi-mode coordination requires
  per-mode integration that benefits from Phase 2 admin-tooling design
  context.
- **Alt B: Skip Wave AE entirely (no ADR)**: rejected. The 3 false
  alarms would be re-flagged by future audits without a documentation
  trail. The verification record is the value.
- **Alt C: Add `MatchPaused` event + `ISchedulerCoordinator` port stub
  now, defer multi-mode integration**: rejected. Stub events that no
  code publishes / subscribes would fail Konsist Rule 55 (ADR-0134)
  publisher-exists check, forcing allowlist entries. Better to land
  the full integration in one wave when Phase 2 admin context is ready.
- **Alt D: Document only in RTM tracker (no ADR)**: rejected. ADRs are
  the canonical Astera record for design decisions including "we
  verified this is wired, do not re-flag" decisions.

## References

- 関連 ADR:
  [[adr/0022-multi-plugin-layout]],
  [[adr/0030-damage-pipeline-and-result-error]] (Wave 5a — established
  ApplyDamageUseCase short-circuit on immunity + friendly-fire check),
  [[adr/0072-pre-paper-port-commitments]] (Wave 5g — established the
  Wave-6-deferred ports including IPlayerMovementGateway /
  IPlayerInputGateway that the AFK detector consumes; not a gap),
  [[adr/0083-anti-cheat-anti-afk]] (Wave 5q — established the
  InMemoryAfkDetector + AfkSweepScheduler infrastructure)
- Related files (Phase A verification citations):
  `plugin/combat/src/main/kotlin/.../usecase/damage/ApplyDamageUseCase.kt:86-114`
  (§A immunity + §B friendly-fire),
  `plugin/core/src/main/kotlin/.../platform/AsteraCorePlugin.kt:1484+1970`
  (§C AFK detector + kick listener wiring),
  `plugin/match/src/main/kotlin/.../domain/match/MatchStateMachine.kt`
  (§D — confirms no Paused state)
- Round 18 audit α #2 false-positive corrections documented here:
  - "Spawn protection enforcement missing" — FALSE
  - "Friendly-fire enforcement missing" — FALSE
  - "AFK detection during match missing" — FALSE
- Future work:
  - **Phase 2 admin tooling wave** (parallel to Wave 8a): land
    `MatchPaused` / `MatchResumed` events + `ISchedulerCoordinator`
    port + per-mode integration + admin commands + Konsist guard rule.
    Estimated 1-2 waves at 8-15h.
