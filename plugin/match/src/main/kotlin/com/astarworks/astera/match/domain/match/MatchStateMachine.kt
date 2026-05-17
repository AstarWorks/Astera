package com.astarworks.astera.match.domain.match

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.match.MatchPhase

/**
 * Pure state machine for [Match] phase transitions.
 *
 * Encodes the legal transition graph for match lifecycle:
 *
 * ```
 * WAITING ─Start──────────────────→ COUNTDOWN
 * COUNTDOWN ─CountdownComplete────→ ACTIVE
 * ACTIVE ─ScoreThresholdReached──→ SUDDEN_DEATH
 * SUDDEN_DEATH ─SuddenDeathTimer─→ ENDED
 * WAITING ─Cancel────────────────→ ENDED
 * COUNTDOWN ─Cancel──────────────→ WAITING
 * ACTIVE ─AdminEnd───────────────→ ENDED
 * SUDDEN_DEATH ─AdminEnd─────────→ ENDED
 * ```
 *
 * All other combinations return [MatchTransitionError].
 */
public object MatchStateMachine {

    /** Triggers that drive phase transitions. */
    public sealed class TransitionTrigger {
        /** Host starts the match from WAITING → COUNTDOWN. */
        public data object Start : TransitionTrigger()

        /** Countdown timer reached zero → ACTIVE. */
        public data object CountdownComplete : TransitionTrigger()

        /** A team reached the score threshold → SUDDEN_DEATH. */
        public data object ScoreThresholdReached : TransitionTrigger()

        /** Sudden-death timer expired → ENDED. */
        public data object SuddenDeathTimer : TransitionTrigger()

        /** Player/host cancels during WAITING or COUNTDOWN. */
        public data object Cancel : TransitionTrigger()

        /** Admin force-ends a live match. */
        public data object AdminEnd : TransitionTrigger()
    }

    /**
     * Attempt to transition from [from] via [trigger].
     *
     * Returns [Result.Success] with the next [MatchPhase], or
     * [Result.Failure] with a [MatchTransitionError].
     */
    public fun transition(
        from: MatchPhase,
        trigger: TransitionTrigger,
    ): Result<MatchPhase, MatchTransitionError> {
        if (from == MatchPhase.ENDED) {
            return Result.failure(MatchTransitionError.MatchAlreadyEnded)
        }

        val next: MatchPhase? = when (trigger) {
            TransitionTrigger.Start ->
                if (from == MatchPhase.WAITING) MatchPhase.COUNTDOWN else null

            TransitionTrigger.CountdownComplete ->
                if (from == MatchPhase.COUNTDOWN) MatchPhase.ACTIVE else null

            TransitionTrigger.ScoreThresholdReached ->
                if (from == MatchPhase.ACTIVE) MatchPhase.SUDDEN_DEATH else null

            TransitionTrigger.SuddenDeathTimer ->
                if (from == MatchPhase.SUDDEN_DEATH) MatchPhase.ENDED else null

            TransitionTrigger.Cancel -> when (from) {
                MatchPhase.WAITING -> MatchPhase.ENDED
                MatchPhase.COUNTDOWN -> MatchPhase.WAITING
                else -> null
            }

            TransitionTrigger.AdminEnd -> when (from) {
                MatchPhase.ACTIVE, MatchPhase.SUDDEN_DEATH -> MatchPhase.ENDED
                else -> null
            }
        }

        return if (next != null) {
            Result.success(next)
        } else {
            Result.failure(MatchTransitionError.IllegalTransition(from, trigger))
        }
    }
}
