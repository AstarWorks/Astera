package com.astarworks.astera.economy.application.eventbus.listeners

import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.economy.application.usecase.currency.DepositUseCase
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.stats.domain.event.XpGranted
import org.slf4j.LoggerFactory

/**
 * Listens for [XpGranted] events (from the Stats plugin) and rewards the player
 * with STAR currency at a rate of max(1, xpAmount / 10).
 */
public class DepositOnXpListener(
    private val deposit: DepositUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: XpGranted) {
        // Match XP is paid out together with its own STAR / GOLD rows (match-rewards.yaml).
        if (event.source == XpSource.MatchWin || event.source == XpSource.MatchParticipation) return
        val reward = (event.amount / 10L).coerceAtLeast(1L)
        val result = deposit.execute(
            playerId = event.playerId,
            currency = Currency.STAR,
            amount = reward,
            reason = CurrencyChangeReason.MissionReward,
        )
        result.onFailure { err ->
            log.debug("DepositOnXpListener: deposit failed for player={} amount={} error={}", event.playerId, reward, err)
        }
    }
}
