package com.astarworks.astera.economy.application.usecase.betting

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingMatchRepository
import com.astarworks.astera.economy.domain.betting.BettingError
import com.astarworks.astera.economy.domain.betting.BettingMatch
import com.astarworks.astera.economy.domain.betting.BettingMatchId
import com.astarworks.astera.economy.domain.betting.BettingStatus

/**
 * The invited [opponentId] declines (or the challenger rescinds) a pending
 * [BettingMatch]; the match moves to
 * [com.astarworks.astera.economy.domain.betting.BettingStatus.Declined].
 *
 * No escrow movement — declining happens *before* `accept` so no funds are
 * held. (Wave 5bet / ADR-0103 §"Workflow" step 2 — opponent decline.)
 */
public class DeclineBettingChallengeUseCase(
    private val matches: IBettingMatchRepository,
) {

    public suspend fun execute(
        bettingMatchId: BettingMatchId,
        actorId: PlayerId,
    ): Result<BettingMatch, BettingError> {
        val match = matches.findById(bettingMatchId)
            ?: return Result.failure(BettingError.UnknownMatch(bettingMatchId))
        if (match.status != BettingStatus.PendingAcceptance) {
            return Result.failure(BettingError.NotPending(bettingMatchId, match.status))
        }
        if (actorId != match.challengerId && actorId != match.opponentId) {
            return Result.failure(BettingError.NotInvited(bettingMatchId, actorId))
        }
        val declined = matches.save(match.decline())
        return Result.success(declined)
    }
}
