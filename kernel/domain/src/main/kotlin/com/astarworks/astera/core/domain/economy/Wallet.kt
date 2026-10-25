package com.astarworks.astera.core.domain.economy

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.attribute.IAttributeContainer
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Per-player balances across all currencies (Wave 5e / ADR-0058 §5).
 *
 * Wave 5e (ADR-0058): facade over [IAttributeContainer]. The wallet no longer
 * owns parallel balance state — every read goes through
 * `container.effectiveNumber(walletKeyFor(c))` and every write goes through
 * `container.set(walletKeyFor(c), NumberValue(...))`. The Postgres overlay in
 * `plugin:persistence` flushes the container to the V14 `player_attributes`
 * JSONB row.
 *
 * Mutating methods (`deposit` / `withdraw`) return `this` because the
 * container is the source of truth — there is no new immutable wallet to
 * construct. Callers that previously relied on `Wallet` being a value object
 * for diff'ing should instead read [balance] before and after the mutation.
 *
 * Withdraws return [Result] because "insufficient funds" is an expected
 * failure mode that callers must handle; deposits cannot fail (a non-negative
 * deposit always succeeds).
 */
public class Wallet(public val container: IAttributeContainer) {

    public fun balance(currency: Currency): Long =
        container.effectiveNumber(walletKeyFor(currency)).toLong().coerceAtLeast(0L)

    public fun deposit(currency: Currency, amount: Long): Wallet {
        require(amount >= 0) { "deposit amount must be >= 0, got $amount" }
        if (amount == 0L) return this
        val key = walletKeyFor(currency)
        val next = balance(currency) + amount
        container.set(key, AttributeValue.NumberValue(next.toDouble()))
        return this
    }

    public fun withdraw(currency: Currency, amount: Long): Result<Wallet, WalletError> {
        require(amount >= 0) { "withdraw amount must be >= 0, got $amount" }
        val have = balance(currency)
        if (have < amount) {
            return Result.failure(WalletError.InsufficientFunds(currency, have, amount))
        }
        val key = walletKeyFor(currency)
        container.set(key, AttributeValue.NumberValue((have - amount).toDouble()))
        return Result.success(this)
    }

    /** Snapshot the balances currently visible on the container. Phase 1 helper for tests. */
    public fun balances(): Map<Currency, Long> =
        Currency.entries.associateWith { balance(it) }.filterValues { it > 0L }

    public companion object {
        /** Wallet wrapping an empty container — see [com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer]. */
        public fun empty(container: IAttributeContainer): Wallet = Wallet(container)

        public fun walletKeyFor(currency: Currency): AttributeKey = when (currency) {
            Currency.GOLD -> Attributes.WALLET_GOLD
            Currency.GEM -> Attributes.WALLET_GEM
        }
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
