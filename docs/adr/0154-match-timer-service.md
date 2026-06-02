---
status: Accepted
date: 2026-06-02
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0154: MatchTimerService — pure-Kotlin impl of IMatchTimerService

## Status

Accepted. Ships Wave 6a-N1 — the production impl of `IMatchTimerService`
(Wave 5g / ADR-0072) — and closes the last sibling-plugin entry in
ADR-0072's Wave 6 deferred allow-set besides `IStructurePaste` (Wave 6b).

## Context

Wave 5g (ADR-0072) committed the `IMatchTimerService` port + a
`FakeMatchTimerService` test fixture so the TimeWarp anomaly variant
(`AnomalyType.TimeWarp` in `ResolveAnomalyEffectUseCase`) had a wireable
signature pre-Wave-6. The port KDoc said the Wave 6a impl would "plug
into the existing `PhaseTimerUseCase` machinery."

`AsteraMatchPlugin.onEnable` never wired any impl — `services.get<IMatchTimerService>()`
returned `null` and `ResolveAnomalyEffectUseCase` had to gate every call
on a null check. TimeWarp anomalies were silently no-ops in production.

Phase 1 PoC closing requires the 30-second COUNTDOWN → ACTIVE auto-
transition (`PhaseTimerUseCase.tickAll` driven by `IScheduler.runRepeatingTicks`)
to honour anomaly-applied shifts so end-to-end smoke tests can exercise
the full match lifecycle including TimeWarp.

## Decision

### §A — Naming: `MatchTimerService`, not `PaperMatchTimerService`

ADR-0072 reserved the Wave 6a impl under "Paper impl" naming and the
ADR-0072 Konsist test (`Wave 6 Paper impl allow-list`) checks that the
impl lives under `*.adapter.paper.*`. But shifting a per-phase timer
needs no Bukkit API — only the match repository (to validate the match
exists) plus an in-memory per-match shift accumulator that
`PhaseTimerUseCase` consults.

Per Phase 1 forward-only posture (CLAUDE.md §Pre-release posture +
.claude/rules/coding-posture.md §"Pre-release breaking changes"), the
impl lands in `plugin/match/.../application/service/MatchTimerService.kt`
(reflecting that the file is pure Kotlin domain coordination) rather
than being forced into `adapter/paper/` for naming-convention
consistency. The Konsist test was updated in the same commit:
`IMatchTimerService` removed from `allowedMissingPaperImpl`, with a
KDoc comment explaining the impl location.

If a future ADR establishes a separate Paper-side concern (e.g. tying
shift application to Bukkit `BossBar` timer-display updates), a
companion `PaperMatchTimerDisplayBridge` adapter can land under
`adapter/paper/` then.

### §B — Shift accumulator design

`MatchTimerService` owns a `ConcurrentHashMap<MatchId, Int>` of
accumulated tick-shifts. The single mutation method:

```kotlin
override fun shiftPhaseTimer(matchId, deltaTicks): Result<Unit, TimerShiftError> {
    matchRepository.findById(matchId)
        ?: return Result.failure(TimerShiftError.UnknownMatch(matchId))
    if (deltaTicks == 0) return Result.success(Unit)
    accumulatedShifts.merge(matchId, deltaTicks) { existing, delta -> existing + delta }
    return Result.success(Unit)
}
```

Two read methods serve `PhaseTimerUseCase`:
- `accumulatedShiftFor(matchId): Int` — consulted in `tickAll` when
  computing elapsed-vs-threshold.
- `clearShiftsFor(matchId)` — called by `tickAll` when a transition
  fires, so the next phase starts with a fresh shift baseline.

Positive shift = phase **extended** (anomaly slows match);
negative shift = phase **shortened**.

### §C — `PhaseTimerUseCase` integration

Constructor gains an optional `matchTimer: MatchTimerService? = null`
parameter. `tickAll` reads `matchTimer?.accumulatedShiftFor(match.id) ?: 0`
and adds it to the per-phase threshold:

```kotlin
val shift = matchTimer?.accumulatedShiftFor(match.id) ?: 0
when (match.phase) {
    COUNTDOWN -> {
        if (now - match.startedAtTick >= PhaseTimings.COUNTDOWN_TICKS + shift) {
            switchPhase.execute(match.id, CountdownComplete)
            matchTimer?.clearShiftsFor(match.id)
        }
    }
    SUDDEN_DEATH -> {
        val enteredAt = suddenDeathEnteredAt.getOrPut(match.id) { now }
        if (now - enteredAt >= PhaseTimings.SUDDEN_DEATH_TICKS + shift) {
            switchPhase.execute(match.id, SuddenDeathTimer)
            matchTimer?.clearShiftsFor(match.id)
        }
    }
    WAITING, ACTIVE, ENDED -> {
        suddenDeathEnteredAt.remove(match.id)
        matchTimer?.clearShiftsFor(match.id)
    }
}
```

The `null` default preserves the pre-existing constructor surface for
`PhaseTimerUseCaseTest` (Wave 4.5 unit tests that don't care about
shifts). Per Phase 1 forward-only this would normally be a no-default
break, but the null default is genuinely useful for tests that exercise
plain phase-timer behaviour.

### §D — `AsteraMatchPlugin.onEnable` wiring

```kotlin
val matchTimer = MatchTimerService(matches)
registrations += services.register<IMatchTimerService>(matchTimer, priority = 0)
val phaseTimer = PhaseTimerUseCase(matches, scheduler, switchPhase, matchTimer)
```

Registered at priority 0 (no overlay) — `MatchTimerService` is the
production impl; no priority-100 Paper overlay is planned. Wiring
sequenced before `PhaseTimerUseCase` construction so the use case
receives the same instance the service registry exposes.

## Consequences

### Positive

- `IMatchTimerService` finally has a production impl. `ResolveAnomalyEffectUseCase`'s
  TimeWarp branch now applies shifts that `PhaseTimerUseCase` honours
  end-to-end. ADR-0072 allow-set shrinks from 2 entries to 1.
- ADR-0149 §C MIRROR_MIN_HP-style invariant (Astera owns the timer, no
  vanilla mechanism interferes) preserved — there is no Bukkit timer
  coupling to break.
- Per-match shifts are GC'd alongside `suddenDeathEnteredAt` when the
  match leaves the timed phase. No leak.

### Neutral / costs

- Naming convention diverges from ADR-0072's "Paper impl" framing.
  Documented in ADR-0072 allow-set comment + this ADR §A. The Konsist
  test continues to enforce its core invariant ("no
  `*.adapter.paper.*` class implements an allow-set port") unchanged.
- `PhaseTimerUseCase` gains a fourth constructor arg. Existing test
  `PhaseTimerUseCaseTest` continues to compile because the new arg
  defaults to `null`.

### Negative

- `MatchTimerService` is a singleton with mutable state. If a future
  wave introduces multi-server match coordination (cross-shard timer
  shift propagation), this impl would need replacement with a
  Postgres-backed or Redis-backed equivalent. Not a Phase 1 concern.

## Verification

- `./gradlew :plugin:match:compileKotlin` green.
- `./gradlew :tools:architecture-test:test` green:
  - ADR-0072 Wave 6 Paper impl allow-list passes with
    `IMatchTimerService` removed.
  - No new Konsist rules required; pure-Kotlin file under
    `application/service/` is already permitted by ADR-0022.
- `./gradlew check` BUILD SUCCESSFUL.

Manual integration check (Wave 6b smoke test): trigger a TimeWarp
anomaly during COUNTDOWN, observe the COUNTDOWN→ACTIVE transition
firing later by the shifted amount.

## References

- [[adr/0022-multi-plugin-architecture]] — sibling-isolation
- [[adr/0027-match-phase-auto-timer]] — Wave 4.5 PhaseTimerUseCase
- [[adr/0072-wave-6-paper-impl-allow-list]] — port + Fake (Wave 5g)
- [[adr/0152-paper-adapter-batch-food-movement-vision-camera-sign]] —
  predecessor allow-set shrink (IPlayerMovementGateway + IPlayerVisionGateway)
