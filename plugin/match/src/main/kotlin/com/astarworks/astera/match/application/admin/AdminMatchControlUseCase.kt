package com.astarworks.astera.match.application.admin

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.domain.match.MatchStateMachine

/** Why an admin-panel match action did not apply. */
public sealed class AdminMatchError {
    public abstract val messageKey: MessageKey

    public data class MatchNotFound(val matchId: MatchId) : AdminMatchError() {
        override val messageKey: MessageKey = MessageKey("astera.match.error.match-not-found")
    }

    /** The match is already ENDED: there is no phase after it. */
    public data class NoNextPhase(val matchId: MatchId) : AdminMatchError() {
        override val messageKey: MessageKey = MessageKey("astera.admin.panel.match.err.no-next-phase")
    }

    /** The lifecycle refused (already ended, transition illegal, ...); [messageKey] is its own key. */
    public data class Lifecycle(val matchId: MatchId, override val messageKey: MessageKey) : AdminMatchError()
}

/**
 * Admin-panel match actions over [IMatchLifecycle] (F1-D, ADR-0182): advance to
 * the next phase, end (a normal admin end), abort (cancelled, no winner).
 * Application use cases, not commands: the panel keeps working if the queue /
 * proxy layer changes how matches are started.
 */
public class AdminMatchControlUseCase(
    private val lifecycle: IMatchLifecycle,
    private val matches: IMatchRepository,
    private val waivers: MatchMinPlayerWaivers,
) {

    /** Fire the trigger that advances the match one phase; returns the new phase. */
    public fun nextPhase(matchId: MatchId): Result<MatchPhase, AdminMatchError> {
        val match = matches.findById(matchId) ?: return Result.failure(AdminMatchError.MatchNotFound(matchId))
        val trigger = nextTrigger(match.phase) ?: return Result.failure(AdminMatchError.NoNextPhase(matchId))
        // An admin pushing a short-handed match out of COUNTDOWN means it.
        if (trigger == MatchStateMachine.TransitionTrigger.CountdownComplete) waivers.waive(matchId)
        return when (val r = lifecycle.switchPhase(matchId, trigger)) {
            is Result.Success -> Result.success(r.value)
            is Result.Failure -> Result.failure(AdminMatchError.Lifecycle(matchId, r.error.messageKey))
        }
    }

    /** End the match as an admin end (winner decided by the score evaluator). */
    public fun end(matchId: MatchId): Result<Unit, AdminMatchError> = finish(matchId, EndReason.AdminEnded)

    /** Abort the match: ended with no winner. */
    public fun abort(matchId: MatchId): Result<Unit, AdminMatchError> =
        finish(matchId, EndReason.Cancelled(ABORT_REASON))

    private fun finish(matchId: MatchId, reason: EndReason): Result<Unit, AdminMatchError> {
        if (matches.findById(matchId) == null) return Result.failure(AdminMatchError.MatchNotFound(matchId))
        return when (val r = lifecycle.end(matchId, reason)) {
            is Result.Success -> Result.success(Unit)
            is Result.Failure -> Result.failure(AdminMatchError.Lifecycle(matchId, r.error.messageKey))
        }
    }

    public companion object {
        public const val ABORT_REASON: String = "admin-panel"

        /** Trigger that moves a match out of [phase], or `null` when it is already ENDED. */
        public fun nextTrigger(phase: MatchPhase): MatchStateMachine.TransitionTrigger? = when (phase) {
            MatchPhase.WAITING -> MatchStateMachine.TransitionTrigger.Start
            MatchPhase.COUNTDOWN -> MatchStateMachine.TransitionTrigger.CountdownComplete
            MatchPhase.ACTIVE -> MatchStateMachine.TransitionTrigger.ScoreThresholdReached
            MatchPhase.SUDDEN_DEATH -> MatchStateMachine.TransitionTrigger.SuddenDeathTimer
            MatchPhase.ENDED -> null
        }
    }
}
