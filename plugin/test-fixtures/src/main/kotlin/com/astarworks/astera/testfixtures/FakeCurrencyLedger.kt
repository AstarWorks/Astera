package com.astarworks.astera.testfixtures

import com.astarworks.astera.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.domain.Result
import com.astarworks.astera.domain.model.economy.Currency
import com.astarworks.astera.domain.model.economy.Wallet
import com.astarworks.astera.domain.model.economy.WalletError
import com.astarworks.astera.domain.model.player.PlayerId

/**
 * In-memory [ICurrencyLedger] for tests. Mirrors the [Wallet] pure-function
 * semantics: debits go through [Wallet.withdraw] and surface
 * [WalletError.InsufficientFunds] without changing balances.
 */
public class FakeCurrencyLedger : ICurrencyLedger {

    private val wallets: MutableMap<PlayerId, Wallet> = mutableMapOf()

    override fun balance(playerId: PlayerId, currency: Currency): Long =
        wallets[playerId]?.balance(currency) ?: 0L

    override fun wallet(playerId: PlayerId): Wallet =
        wallets[playerId] ?: Wallet.EMPTY

    override fun debit(playerId: PlayerId, currency: Currency, amount: Long): Result<Wallet, WalletError> =
        wallet(playerId).withdraw(currency, amount).onSuccess { wallets[playerId] = it }

    override fun credit(playerId: PlayerId, currency: Currency, amount: Long): Wallet {
        val next = wallet(playerId).deposit(currency, amount)
        wallets[playerId] = next
        return next
    }
}
