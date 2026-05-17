package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.TransferResult
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.economy.Wallet
import com.astarworks.astera.core.domain.economy.WalletError
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

    /**
     * In-memory transfer.
     *
     * Atomicity strategy: debit `from` under [ConcurrentHashMap.compute]'s
     * per-key lock (so the balance check + withdraw are atomic for the source
     * wallet); credit `to` afterwards. The two-step is not a single
     * cross-entry critical section, but because [Wallet.deposit] always
     * succeeds for a non-negative amount, there is no failure path that would
     * require compensating the debit. If `from == to` is ever passed in
     * (callers must reject it, but this method is defensive), the debit
     * lambda would run first and then a deposit would be applied to the same
     * map slot — still consistent because the deposit happens after `compute`
     * has released the lock and operates on the post-debit state.
     */
    override fun transfer(
        from: PlayerId,
        to: PlayerId,
        currency: Currency,
        amount: Long,
    ): Result<TransferResult, WalletError> {
        require(amount >= 0) { "transfer amount must be >= 0, got $amount" }
        var debitOutcome: Result<Wallet, WalletError>? = null
        store.compute(from) { _, existing ->
            val w = existing ?: Wallet.EMPTY
            val attempt = w.withdraw(currency, amount)
            debitOutcome = attempt
            if (attempt.isSuccess) attempt.getOrNull() ?: w else w
        }
        val outcome = debitOutcome ?: Result.success(Wallet.EMPTY)
        return when (outcome) {
            is Result.Failure -> Result.failure(outcome.error)
            is Result.Success -> {
                val fromWallet = outcome.value
                val toWallet = store.compute(to) { _, existing ->
                    val w = existing ?: Wallet.EMPTY
                    w.deposit(currency, amount)
                } ?: Wallet.EMPTY
                Result.success(TransferResult(fromWallet = fromWallet, toWallet = toWallet))
            }
        }
    }
}
