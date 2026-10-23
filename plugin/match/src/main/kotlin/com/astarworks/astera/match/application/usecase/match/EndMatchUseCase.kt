package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase

/**
 * Ends a match, publishing [MatchEnded].
 *
 * Accepts any live match phase; ENDED → immediate failure.
 */
public class EndMatchUseCase(
    private val matches: IMatchRepository,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
    private val victoryEvaluator: VictoryConditionEvaluator = VictoryConditionEvaluator(),
) {

    public fun execute(matchId: MatchId, reason: EndReason): Result<Unit, EndMatchError> {
        val match = matches.findById(matchId)
            ?: return Result.failure(EndMatchError.MatchNotFound(matchId))

        if (match.phase == MatchPhase.ENDED) {
            return Result.failure(EndMatchError.MatchAlreadyEnded(matchId))
        }

        val ended = match.withPhase(MatchPhase.ENDED)
        matches.save(ended)

        // Wave 4.5: compute winning team via score threshold evaluator (ADR-0027)
        // A mode that decided the match itself (WP10 Annihilation) carries its winner.
        val winningTeam = when (reason) {
            is EndReason.TeamVictory -> reason.winner
            is EndReason.NormaReached -> reason.winner
            is EndReason.NormaTimeLimit -> reason.leader
            is EndReason.EncounterCleared -> reason.winner
            is EndReason.EncounterFailed -> reason.opponent
            else -> victoryEvaluator.evaluate(ended)
        }

        eventBus.publish(
            MatchEnded(
                matchId = matchId,
                winningTeam = winningTeam,
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
