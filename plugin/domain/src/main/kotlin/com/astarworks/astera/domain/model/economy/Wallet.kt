package com.astarworks.astera.domain.model.economy

import com.astarworks.astera.domain.Result
import com.astarworks.astera.domain.model.i18n.MessageKey

/**
 * Per-player balances across all currencies.
 *
 * Operations are pure: every mutation returns a new [Wallet]. Persistence is
 * the adapter layer's concern via [com.astarworks.astera.application.port.outbound.ICurrencyLedger].
 *
 * Withdraws return [Result] because "insufficient funds" is an expected
 * failure mode that callers must handle; deposits cannot fail (a non-negative
 * deposit always succeeds).
 */
public data class Wallet(val balances: Map<Currency, Long> = emptyMap()) {

    init {
        balances.forEach { (c, amount) ->
            require(amount >= 0) { "$c balance must be >= 0, got $amount" }
        }
    }

    public fun balance(currency: Currency): Long = balances[currency] ?: 0L

    public fun deposit(currency: Currency, amount: Long): Wallet {
        require(amount >= 0) { "deposit amount must be >= 0, got $amount" }
        if (amount == 0L) return this
        val next = balances.toMutableMap()
        next[currency] = balance(currency) + amount
        return copy(balances = next)
    }

    public fun withdraw(currency: Currency, amount: Long): Result<Wallet, WalletError> {
        require(amount >= 0) { "withdraw amount must be >= 0, got $amount" }
        val have = balance(currency)
        if (have < amount) {
            return Result.failure(WalletError.InsufficientFunds(currency, have, amount))
        }
        val next = balances.toMutableMap()
        next[currency] = have - amount
        return Result.success(copy(balances = next))
    }

    public companion object {
        public val EMPTY: Wallet = Wallet(emptyMap())
    }
}

/** Sealed failure mode for wallet ops. */
public sealed class WalletError {
    public abstract val messageKey: MessageKey

    public data class InsufficientFunds(
        val currency: Currency,
        val have: Long,
        val tried: Long,
    ) : WalletError() {
        override val messageKey: MessageKey = MessageKey("astera.economy.insufficient_funds")
    }
}
