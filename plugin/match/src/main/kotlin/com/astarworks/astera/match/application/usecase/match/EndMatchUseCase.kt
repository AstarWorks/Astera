package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.match.domain.event.MatchEnded
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.match.domain.match.MatchStateMachine

/**
 * Ends a match, publishing [MatchEnded].
 *
 * Accepts any live match phase; ENDED → immediate failure.
 */
public class EndMatchUseCase(
    private val matches: IMatchRepository,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
) {

    public fun execute(matchId: MatchId, reason: EndReason): Result<Unit, EndMatchError> {
        val match = matches.findById(matchId)
            ?: return Result.failure(EndMatchError.MatchNotFound(matchId))

        if (match.phase == MatchPhase.ENDED) {
            return Result.failure(EndMatchError.MatchAlreadyEnded(matchId))
        }

        val trigger = when (reason) {
            EndReason.AdminEnded -> MatchStateMachine.TransitionTrigger.AdminEnd
            is EndReason.Cancelled -> MatchStateMachine.TransitionTrigger.Cancel
            else -> MatchStateMachine.TransitionTrigger.AdminEnd
        }

        val transitionResult = MatchStateMachine.transition(match.phase, trigger)
        val nextPhase = when (transitionResult) {
            is Result.Success -> transitionResult.value
            is Result.Failure -> MatchPhase.ENDED
        }

        val ended = match.withPhase(nextPhase)
        matches.save(ended)

        eventBus.publish(
            MatchEnded(
                matchId = matchId,
                winningTeam = null,
                atTick = scheduler.currentTick(),
                reason = reason,
            ),
        )

        return Result.success(Unit)
    }
}

/** Expected failure modes of [EndMatchUseCase.execute]. */
public sealed class EndMatchError {
    public abstract val messageKey: MessageKey

    public data class MatchNotFound(val matchId: MatchId) : EndMatchError() {
        override val messageKey: MessageKey = MessageKey("astera.match.error.match-not-found")
    }

    public data class MatchAlreadyEnded(val matchId: MatchId) : EndMatchError() {
        override val messageKey: MessageKey = MessageKey("astera.match.error.match-already-ended")
    }
}
