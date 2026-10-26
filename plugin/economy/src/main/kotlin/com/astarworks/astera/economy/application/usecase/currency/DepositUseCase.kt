package com.astarworks.astera.economy.application.usecase.currency

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.economy.Wallet
import com.astarworks.astera.core.domain.event.CurrencyChanged

/**
 * Deposits a positive amount into a player's wallet and publishes [CurrencyChanged].
 *
 * Returns [DepositError.InvalidAmount] if [amount] is not > 0.
 */
public class DepositUseCase(
    private val ledger: ICurrencyLedger,
    private val eventBus: IDomainEventBus,
) {
    public suspend fun execute(
        playerId: PlayerId,
        currency: Currency,
        amount: Long,
        reason: CurrencyChangeReason,
    ): Result<Wallet, DepositError> {
        if (amount <= 0L) {
            return Result.failure(DepositError.InvalidAmount(amount))
        }
        val wallet = ledger.credit(playerId, currency, amount)
        eventBus.publish(
            CurrencyChanged(
                playerId = playerId,
                currency = currency,
                delta = amount,
                newBalance = wallet.balance(currency),
                reason = reason,
            ),
        )
        return Result.success(wallet)
    }
}

/** Expected failure modes of [DepositUseCase.execute]. */
public sealed class DepositError {
    public abstract val messageKey: MessageKey

    public data class InvalidAmount(val amount: Long) : DepositError() {
        override val messageKey: MessageKey = MessageKey("astera.economy.invalid_amount")
    }
}
