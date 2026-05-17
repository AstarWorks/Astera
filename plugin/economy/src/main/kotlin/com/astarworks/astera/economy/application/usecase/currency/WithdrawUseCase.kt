package com.astarworks.astera.economy.application.usecase.currency

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.economy.domain.economy.Currency
import com.astarworks.astera.economy.domain.economy.Wallet
import com.astarworks.astera.economy.domain.economy.WalletError
import com.astarworks.astera.economy.domain.event.CurrencyChanged

/**
 * Debits a positive amount from a player's wallet and publishes [CurrencyChanged].
 *
 * Returns [WithdrawError.InvalidAmount] if [amount] is not > 0.
 * Returns [WithdrawError.InsufficientFunds] if the player's balance is too low.
 */
public class WithdrawUseCase(
    private val ledger: ICurrencyLedger,
    private val eventBus: IDomainEventBus,
) {
    public fun execute(
        playerId: PlayerId,
        currency: Currency,
        amount: Long,
        reason: CurrencyChangeReason,
    ): Result<Wallet, WithdrawError> {
        if (amount <= 0L) {
            return Result.failure(WithdrawError.InvalidAmount(amount))
        }
        return when (val debitResult = ledger.debit(playerId, currency, amount)) {
            is Result.Success -> {
                val wallet = debitResult.value
                eventBus.publish(
                    CurrencyChanged(
                        playerId = playerId,
                        currency = currency,
                        delta = -amount,
                        newBalance = wallet.balance(currency),
                        reason = reason,
                    ),
                )
                Result.success(wallet)
            }
            is Result.Failure -> {
                val walletErr = debitResult.error
                when (walletErr) {
                    is WalletError.InsufficientFunds -> Result.failure(
                        WithdrawError.InsufficientFunds(
                            playerId = playerId,
                            currency = currency,
                            balance = walletErr.have,
                            requested = walletErr.tried,
                        ),
                    )
                }
            }
        }
    }
}

/** Expected failure modes of [WithdrawUseCase.execute]. */
public sealed class WithdrawError {
    public abstract val messageKey: MessageKey

    public data class InvalidAmount(val amount: Long) : WithdrawError() {
        override val messageKey: MessageKey = MessageKey("astera.economy.invalid_amount")
    }

    public data class InsufficientFunds(
        val playerId: PlayerId,
        val currency: Currency,
        val balance: Long,
        val requested: Long,
    ) : WithdrawError() {
        override val messageKey: MessageKey = MessageKey("astera.economy.insufficient_funds")
    }
}
