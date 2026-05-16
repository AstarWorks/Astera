package com.astarworks.astera.application.port.outbound

import com.astarworks.astera.domain.Result
import com.astarworks.astera.domain.model.economy.Currency
import com.astarworks.astera.domain.model.economy.Wallet
import com.astarworks.astera.domain.model.economy.WalletError
import com.astarworks.astera.domain.model.player.PlayerId

/**
 * Outbound port: read and mutate a player's currency balances.
 *
 * The ledger is the persistent source of truth for per-(player, currency)
 * balances. The pure [Wallet] domain object is what callers receive on a
 * read — it can be combined with [Wallet.withdraw] / [Wallet.deposit] to
 * produce a new balance to save back.
 *
 * Phase 2 mid implements this on top of `adapter-persistence-postgres`.
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
}
