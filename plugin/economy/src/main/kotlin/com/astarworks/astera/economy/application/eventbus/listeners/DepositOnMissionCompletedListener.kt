package com.astarworks.astera.economy.application.eventbus.listeners

import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.economy.application.usecase.currency.DepositUseCase
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.stats.domain.event.MissionCompleted
import org.slf4j.LoggerFactory

/**
 * Subscribes to [MissionCompleted] (published by Stats) and deposits the
 * Norma's currency reward into the player's wallet.
 *
 * Per ADR-0022, the Norma reward carries `currencyId: String?` (e.g. "GOLD")
 * rather than the [Currency] enum because Stats must not import economy.*.
 * This listener performs the mapping; an unknown / null currencyId is logged
 * and skipped (no exception — robustness over strictness).
 *
 * silly-knuth §5: subscribes to in-process bus, publishes to in-process bus
 * (no broadcaster).
 */
public class DepositOnMissionCompletedListener(
    private val deposit: DepositUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public suspend fun handle(event: MissionCompleted) {
        val amount = event.reward.currencyAmount
        if (amount <= 0L) {
            log.debug(
                "DepositOnMissionCompletedListener: norma {} for {} has no currency reward (amount={}); skipping",
                event.normaId, event.playerId, amount,
            )
            return
        }
        val currencyId = event.reward.currencyId
        if (currencyId.isNullOrBlank()) {
            log.debug(
                "DepositOnMissionCompletedListener: norma {} for {} has null currencyId; skipping",
                event.normaId, event.playerId,
            )
            return
        }
        val currency = parseCurrency(currencyId)
        if (currency == null) {
            log.debug(
                "DepositOnMissionCompletedListener: unknown currencyId='{}' on norma {} for player {}; skipping",
                currencyId, event.normaId, event.playerId,
            )
            return
        }
        val result = deposit.execute(
            playerId = event.playerId,
            currency = currency,
            amount = amount,
            reason = CurrencyChangeReason.MissionReward,
        )
        result.onFailure { err ->
            log.debug(
                "DepositOnMissionCompletedListener: deposit failed for player={} norma={} amount={} err={}",
                event.playerId, event.normaId, amount, err,
            )
        }
    }

    /** Map the Stats-side string currencyId to the local [Currency] enum. */
    private fun parseCurrency(currencyId: String): Currency? =
        Currency.entries.firstOrNull { it.name.equals(currencyId, ignoreCase = true) }
}
