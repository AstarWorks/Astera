package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.match.application.service.MatchTimerService
import com.astarworks.astera.match.domain.match.MatchStateMachine
import com.astarworks.astera.match.domain.match.PhaseTimings
import java.util.concurrent.ConcurrentHashMap

/**
 * Wave 4.5 (R3.5, ADR-0027): auto-advance match phases based on elapsed ticks.
 *
 * Designed to be invoked from a repeating [IScheduler] task wired in
 * `AsteraMatchPlugin.onEnable`. The use case itself is pure Kotlin — it never
 * imports Paper APIs and never schedules its own tasks; the platform layer
 * decides cadence (typically once per second, i.e. `runRepeatingTicks(20, 20)`).
 *
 * See ADR-0019 (Unified Scheduler) for the absolute-tick policy: this use case
 * reads `scheduler.currentTick()` and compares against per-match start ticks
 * rather than relying on relative delays. That makes it survive plugin reloads
 * and lets [tickAll] be safely re-run after server lag without double-firing.
 *
 * Phase transitions delegated:
 * - `COUNTDOWN` → `ACTIVE` via [MatchStateMachine.TransitionTrigger.CountdownComplete]
 *   when `currentTick - match.startedAtTick >= COUNTDOWN_TICKS`.
 * - `SUDDEN_DEATH` → `ENDED` via [MatchStateMachine.TransitionTrigger.SuddenDeathTimer]
 *   when `currentTick - suddenDeathStartTick >= SUDDEN_DEATH_TICKS`. Because the
 *   domain [com.astarworks.astera.core.domain.match.Match] only carries
 *   `startedAtTick` (the WAITING entry tick), this use case maintains an
 *   in-memory map of `matchId → suddenDeathEnteredAt` populated lazily on the
 *   first tick where the match is observed in SUDDEN_DEATH. The map is cleared
 *   on transition out. Phase 3 will replace this with a `phaseStartedAtTick`
 *   field on [com.astarworks.astera.core.domain.match.Match] (or full
 *   phase-history via event sourcing).
 */
public class PhaseTimerUseCase(
    private val matchRepository: IMatchRepository,
    private val scheduler: IScheduler,
    private val switchPhase: SwitchPhaseUseCase,
    /**
     * Wave 6a-N1 (ADR-0154): optional shift accumulator consulted when
     * computing elapsed-vs-threshold for COUNTDOWN / SUDDEN_DEATH
     * transitions. Null = no shifts applied (legacy / test paths that
     * predate the `IMatchTimerService` impl).
     */
    private val matchTimer: MatchTimerService? = null,
    /** COUNTDOWN length in ticks (WP8: `match_countdown_seconds` of the queue config). */
    private val countdownTicks: () -> Long = { PhaseTimings.COUNTDOWN_TICKS },
    /**
     * G1: what to do when SUDDEN_DEATH runs out. A bare phase switch to ENDED never published
     * `MatchEnded`, so players stayed in the arena with no result, no rewards and no way back; the
     * platform wires this to the real end-of-match path. Null keeps the legacy phase switch (tests).
     */
    private val onSuddenDeathExpired: ((MatchId) -> Unit)? = null,
    /**
     * A COUNTDOWN that stays below the roster floor ([SwitchPhaseError.InsufficientPlayers]) for
     * [INSUFFICIENT_GRACE_TICKS] is cancelled through this hook (players are returned / told) instead of
     * hanging in COUNTDOWN forever. Null keeps the legacy "retry every sweep" behaviour (tests).
     */
    private val onInsufficientPlayers: ((MatchId) -> Unit)? = null,
) {

    /** First sweep at which a match's countdown was blocked by the roster floor. */
    private val insufficientSince: MutableMap<MatchId, Long> = ConcurrentHashMap()

    /**
     * In-memory cache of when each match first entered SUDDEN_DEATH.
     *
     * Limitation: if the plugin restarts while a match is in SUDDEN_DEATH the
     * timer effectively resets — the entry gets re-populated with the
     * post-restart tick. Acceptable for Wave 4.5 (matches are not persisted
     * across restarts in Phase 2); Phase 3 with persistent matches will need
     * either an event-sourced rebuild or a dedicated domain field.
     */
    private val suddenDeathEnteredAt: MutableMap<MatchId, Long> = ConcurrentHashMap()

    /**
     * Sweep every active match, applying timer-based transitions.
     *
     * Safe to call every tick — each match is independently inspected and the
     * underlying [SwitchPhaseUseCase] is a no-op once the phase has advanced.
     * Failures from [SwitchPhaseUseCase.execute] are silently dropped here;
     * `PhaseChanged` already provides the auditable signal for successful
     * transitions, and illegal-transition errors are expected during normal
     * lifecycle (e.g. an admin manually advanced the phase first).
     */
    public fun tickAll() {
        val now = scheduler.currentTick()
        val active = matchRepository.findActiveMatches()

        for (match in active) {
            // Wave 6a-N1 (ADR-0154): MatchTimerService accumulates per-match
            // tick shifts (positive = phase extended by anomaly; negative =
            // phase shortened). Added to the phase threshold so positive
            // shift makes the transition fire later.
            val shift = matchTimer?.accumulatedShiftFor(match.id) ?: 0

            when (match.phase) {
                MatchPhase.COUNTDOWN -> {
                    if (now - match.startedAtTick >= countdownTicks() + shift) {
                        val outcome = switchPhase.execute(
                            match.id,
                            MatchStateMachine.TransitionTrigger.CountdownComplete,
                        )
                        val blocked = (outcome as? Result.Failure)?.error is SwitchPhaseError.InsufficientPlayers
                        if (blocked) {
                            val since = insufficientSince.getOrPut(match.id) { now }
                            val cancel = onInsufficientPlayers
                            if (cancel != null && now - since >= INSUFFICIENT_GRACE_TICKS) {
                                insufficientSince.remove(match.id)
                                cancel(match.id)
                            }
                        } else {
                            insufficientSince.remove(match.id)
                        }
                        matchTimer?.clearShiftsFor(match.id)
                    }
                }

                MatchPhase.SUDDEN_DEATH -> {
                    val enteredAt = suddenDeathEnteredAt.getOrPut(match.id) { now }
                    if (now - enteredAt >= PhaseTimings.SUDDEN_DEATH_TICKS + shift) {
                        val end = onSuddenDeathExpired
                        if (end != null) {
                            suddenDeathEnteredAt.remove(match.id)
                            end(match.id)
                        } else {
                            switchPhase.execute(
                                match.id,
                                MatchStateMachine.TransitionTrigger.SuddenDeathTimer,
                            )
                        }
                        matchTimer?.clearShiftsFor(match.id)
                    }
                }

                MatchPhase.WAITING,
                MatchPhase.ACTIVE,
                MatchPhase.ENDED,
                -> {
                    // No timer-driven transitions in these phases.
                    // Drop any stale SUDDEN_DEATH tracking if the match left
                    // that phase via a non-timer trigger (admin end, etc.).
                    suddenDeathEnteredAt.remove(match.id)
                    matchTimer?.clearShiftsFor(match.id)
                }
            }
        }

        // Garbage-collect tracking for matches that are no longer active.
        val activeIds: Set<MatchId> = active.mapTo(mutableSetOf()) { it.id }
        suddenDeathEnteredAt.keys.retainAll(activeIds)
        insufficientSince.keys.retainAll(activeIds)
    }

    private companion object {
        /** How long a blocked countdown waits for late arrivals before the match is cancelled (20 s). */
        const val INSUFFICIENT_GRACE_TICKS: Long = 400L
    }
}
