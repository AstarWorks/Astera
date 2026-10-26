package com.astarworks.astera.economy.application.usecase.currency

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.economy.WalletError
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.event.CurrencyChanged

/**
 * Transfers a positive amount from [from] to [to] and publishes two [CurrencyChanged] events.
 *
 * Wave 3 (R2) / ADR-0022: delegates the atomic balance shuffle to
 * [ICurrencyLedger.transfer] in a single call. The Postgres impl wraps debit
 * and credit in one SQL transaction; the in-memory impl uses per-key locks
 * and cannot fail on the credit side. Either way this use case observes a
 * single atomic outcome (success-with-both-wallets, or insufficient-funds)
 * and no longer needs compensating refund logic.
 */
public class TransferUseCase(
    private val ledger: ICurrencyLedger,
    private val eventBus: IDomainEventBus,
) {
    public suspend fun execute(
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
        return when (val outcome = ledger.transfer(from, to, currency, amount)) {
            is Result.Failure -> when (val err = outcome.error) {
                is WalletError.InsufficientFunds -> Result.failure(
                    TransferError.InsufficientFunds(
                        playerId = from,
                        currency = currency,
                        balance = err.have,
                        requested = err.tried,
                    ),
                )
            }
            is Result.Success -> {
                val (fromWallet, toWallet) = outcome.value
                eventBus.publish(
                    CurrencyChanged(
                        playerId = from,
                        currency = currency,
                        delta = -amount,
                        newBalance = fromWallet.balance(currency),
                        reason = CurrencyChangeReason.Transfer(counterpartyId = to),
                    ),
                )
                eventBus.publish(
                    CurrencyChanged(
                        playerId = to,
                        currency = currency,
                        delta = +amount,
                        newBalance = toWallet.balance(currency),
                        reason = CurrencyChangeReason.Transfer(counterpartyId = from),
                    ),
                )
                Result.success(Unit)
            }
        }
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
