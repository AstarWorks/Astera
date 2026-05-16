package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.economy.domain.economy.Currency
import com.astarworks.astera.economy.domain.economy.Wallet
import com.astarworks.astera.economy.domain.economy.WalletError
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [ICurrencyLedger] default. Lives in production scope so feature
 * plugins can wire it up directly at startup; the Persistence plugin
 * (Stage 4-改) registers a Postgres-backed override with higher priority.
 *
 * Empty wallets are materialized on read so callers always receive a
 * non-null [Wallet]. Concurrency: backed by [ConcurrentHashMap], all writes
 * are mutex-free, but multi-step ops (debit / credit) replace the wallet
 * with `compute` under the per-key lock that [ConcurrentHashMap] gives.
 */
public class InMemoryCurrencyLedger : ICurrencyLedger {

    private val store: MutableMap<PlayerId, Wallet> = ConcurrentHashMap()

    override fun balance(playerId: PlayerId, currency: Currency): Long =
        store[playerId]?.balance(currency) ?: 0L

    override fun wallet(playerId: PlayerId): Wallet =
        store[playerId] ?: Wallet.EMPTY

    override fun debit(
        playerId: PlayerId,
        currency: Currency,
        amount: Long,
    ): Result<Wallet, WalletError> {
        require(amount >= 0) { "debit amount must be >= 0, got $amount" }
        var outcome: Result<Wallet, WalletError>? = null
        store.compute(playerId) { _, existing ->
            val w = existing ?: Wallet.EMPTY
            val attempt = w.withdraw(currency, amount)
            outcome = attempt
            if (attempt.isSuccess) attempt.getOrNull() ?: w else w
        }
        return outcome ?: Result.success(Wallet.EMPTY)
    }

    override fun credit(playerId: PlayerId, currency: Currency, amount: Long): Wallet {
        require(amount >= 0) { "credit amount must be >= 0, got $amount" }
        val next = store.compute(playerId) { _, existing ->
            val w = existing ?: Wallet.EMPTY
            w.deposit(currency, amount)
        }
        return next ?: Wallet.EMPTY
    }
}
