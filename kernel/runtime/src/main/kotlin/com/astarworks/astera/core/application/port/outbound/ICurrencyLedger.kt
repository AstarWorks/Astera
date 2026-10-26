package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.economy.Wallet
import com.astarworks.astera.core.domain.economy.WalletError

/**
 * Outbound port: read and mutate a player's currency balances.
 *
 * The ledger is the persistent source of truth for per-(player, currency)
 * balances. The pure [Wallet] domain object is what callers receive on a
 * read — it can be combined with [Wallet.withdraw] / [Wallet.deposit] to
 * produce a new balance to save back.
 *
 * Phase 4 / Stage 4-改 implements this on top of `:plugin:persistence` with
 * Postgres-backed storage.
 */
public interface ICurrencyLedger {

    public fun balance(playerId: PlayerId, currency: Currency): Long

    public fun wallet(playerId: PlayerId): Wallet

    /**
     * Atomically debit [amount] from the player's balance. Fails with
     * [WalletError.InsufficientFunds] if the balance is too low.
     */
    public fun debit(playerId: PlayerId, currency: Currency, amount: Long): Result<Wallet, WalletError>

    /** Credit a non-negative [amount]. Negative amounts are a programming error and throw. */
    public fun credit(playerId: PlayerId, currency: Currency, amount: Long): Wallet

    /**
     * Atomically transfer [amount] of [currency] from [from] to [to]. Implementations
     * must guarantee that either both wallets are updated or neither is. The Postgres
     * adapter wraps debit + credit in a single SQL transaction; the in-memory adapter
     * does debit-then-credit and refunds [from] on the (currently unreachable) credit
     * failure path.
     *
     * Fails with [WalletError.InsufficientFunds] when [from]'s balance is below [amount].
     * Caller is responsible for rejecting `from == to` and non-positive [amount] before
     * calling (TransferUseCase enforces these).
     */
    public fun transfer(
        from: PlayerId,
        to: PlayerId,
        currency: Currency,
        amount: Long,
    ): Result<TransferResult, WalletError>
}

/** Both wallet snapshots after a successful atomic [ICurrencyLedger.transfer]. */
public data class TransferResult(
    val fromWallet: Wallet,
    val toWallet: Wallet,
)
