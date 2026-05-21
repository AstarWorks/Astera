package com.astarworks.astera.economy.application.eventbus.listeners

import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.economy.application.usecase.currency.DepositUseCase
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.event.MatchEnded
import org.slf4j.LoggerFactory

/**
 * On [MatchEnded] credits every member of the winning team with [rewardAmount]
 * units of [rewardCurrency] via [DepositUseCase].
 *
 * Resolves the team roster via [IMatchRepository] because [MatchEnded] only
 * carries the winning [com.astarworks.astera.core.domain.match.TeamId], not
 * the player list. A draw ([MatchEnded.winningTeam] == null) pays nothing.
 *
 * The reward amount is currently a static constructor parameter; Phase 3
 * stage configuration under content/stages is expected to grow a
 * `win_reward` field so each stage can override this. Until then the wire
 * site supplies the value (default 100 STAR in
 * `AsteraEconomyPlugin.onEnable`).
 *
 * silly-knuth §5: subscribes to in-process [com.astarworks.astera.core.application.port.outbound.IDomainEventBus]
 * and delegates to a use case that publishes [com.astarworks.astera.core.domain.event.CurrencyChanged] on the
 * same in-process bus (no external fan-out from this listener).
 */
public class RewardOnMatchEndedListener(
    private val matchRepository: IMatchRepository,
    private val deposit: DepositUseCase,
    private val rewardAmount: Long,
    private val rewardCurrency: Currency = Currency.STAR,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: MatchEnded) {
        if (rewardAmount <= 0L) {
            log.debug("RewardOnMatchEndedListener: rewardAmount={} <= 0, skipping", rewardAmount)
            return
        }
        val winningTeam = event.winningTeam
        if (winningTeam == null) {
            log.debug("RewardOnMatchEndedListener: match {} ended as draw, no reward paid", event.matchId)
            return
        }
        val match = matchRepository.findById(event.matchId)
        if (match == null) {
            log.debug("RewardOnMatchEndedListener: match {} not found", event.matchId)
            return
        }
        val winners = match.teams.firstOrNull { it.id == winningTeam }?.members.orEmpty()
        for (playerId in winners) {
            val result = deposit.execute(
                playerId = playerId,
                currency = rewardCurrency,
                amount = rewardAmount,
                reason = CurrencyChangeReason.MatchReward,
            )
            result.onFailure { err ->
                log.debug(
                    "RewardOnMatchEndedListener: deposit failed for player={} match={} amount={} err={}",
                    playerId, event.matchId, rewardAmount, err,
                )
            }
        }
    }
}
