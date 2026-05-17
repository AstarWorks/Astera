package com.astarworks.astera.economy.application.usecase.currency

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.economy.domain.economy.Currency
import com.astarworks.astera.economy.domain.economy.WalletError
import com.astarworks.astera.economy.domain.event.CurrencyChanged

/**
 * Transfers a positive amount from [from] to [to] and publishes two [CurrencyChanged] events.
 *
 * Compensating refund logic is a no-op for Stage 3-改 because [ICurrencyLedger.credit]
 * (InMemoryCurrencyLedger) cannot fail. If credit fails in a future Postgres impl,
 * [ledger.credit] should refund [from] — deferred to Stage 4-改.
 */
public class TransferUseCase(
    private val ledger: ICurrencyLedger,
    private val eventBus: IDomainEventBus,
) {
    public fun execute(
        from: PlayerId,
        to: PlayerId,
        currency: Currency,
        amount: Long,
    ): Result<Unit, TransferError> {
        if (amount <= 0L) {
            return Result.failure(TransferError.InvalidAmount(amount))
        }
        if (from == to) {
            return Result.failure(TransferError.SameAccount(from))
        }
        // Debit source
        val debitResult = ledger.debit(from, currency, amount)
        if (debitResult is Result.Failure) {
            val walletErr = debitResult.error
            return when (walletErr) {
                is WalletError.InsufficientFunds -> Result.failure(
                    TransferError.InsufficientFunds(
                        playerId = from,
                        currency = currency,
                        balance = walletErr.have,
                        requested = walletErr.tried,
                    ),
                )
            }
        }
        val fromWallet = (debitResult as Result.Success).value

        // Credit target (cannot fail with InMemoryCurrencyLedger)
        val toWallet = ledger.credit(to, currency, amount)
        // Compensating: if credit fails in future impl → ledger.credit(from, currency, amount)

        // Publish debit event for source
        eventBus.publish(
            CurrencyChanged(
                playerId = from,
                currency = currency,
                delta = -amount,
                newBalance = fromWallet.balance(currency),
                reason = CurrencyChangeReason.Transfer(counterpartyId = to),
            ),
        )
        // Publish credit event for target
        eventBus.publish(
            CurrencyChanged(
                playerId = to,
                currency = currency,
                delta = +amount,
                newBalance = toWallet.balance(currency),
                reason = CurrencyChangeReason.Transfer(counterpartyId = from),
            ),
        )
        return Result.success(Unit)
    }
}

/** Expected failure modes of [TransferUseCase.execute]. */
public sealed class TransferError {
    public abstract val messageKey: MessageKey

    public data class InvalidAmount(val amount: Long) : TransferError() {
        override val messageKey: MessageKey = MessageKey("astera.economy.invalid_amount")
    }

    public data class SameAccount(val playerId: PlayerId) : TransferError() {
        override val messageKey: MessageKey = MessageKey("astera.economy.transfer_same_account")
    }

    public data class InsufficientFunds(
        val playerId: PlayerId,
        val currency: Currency,
        val balance: Long,
        val requested: Long,
    ) : TransferError() {
        override val messageKey: MessageKey = MessageKey("astera.economy.insufficient_funds")
    }
}
