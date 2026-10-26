package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.TransferResult
import com.astarworks.astera.core.application.service.InMemoryPlayerAttributeContainer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.economy.Wallet
import com.astarworks.astera.core.domain.economy.WalletError
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory [ICurrencyLedger] default. Wave 5e (ADR-0058 §5): every operation
 * delegates to a [Wallet] facade over [IPlayerAttributeContainerStore]; there
 * is no parallel `Map<PlayerId, Wallet>` store anymore. The Persistence plugin
 * (Stage 4-改) registers a Postgres-backed override with higher priority that
 * swaps the container store underneath.
 *
 * Concurrency: `IAttributeContainer` is main-thread-only by contract; this
 * impl follows the same rule (no internal locking). Background regen tasks
 * must marshal back to the main thread via `IScheduler` before mutating.
 */
public class InMemoryCurrencyLedger(
    private val containerStore: IPlayerAttributeContainerStore = InMemoryPlayerAttributeContainer(),
) : ICurrencyLedger {

    private fun walletFor(playerId: PlayerId): Wallet =
        Wallet(containerStore.containerFor(playerId))

    override fun balance(playerId: PlayerId, currency: Currency): Long =
        walletFor(playerId).balance(currency)

    override fun wallet(playerId: PlayerId): Wallet = walletFor(playerId)

    override suspend fun debit(
        playerId: PlayerId,
        currency: Currency,
        amount: Long,
    ): Result<Wallet, WalletError> {
        require(amount >= 0) { "debit amount must be >= 0, got $amount" }
        return walletFor(playerId).withdraw(currency, amount)
    }

    override suspend fun credit(playerId: PlayerId, currency: Currency, amount: Long): Wallet {
        require(amount >= 0) { "credit amount must be >= 0, got $amount" }
        return walletFor(playerId).deposit(currency, amount)
    }

    /**
     * In-memory transfer. Debit `from` first (atomic at the container-set call
     * site), credit `to` second. Because `IAttributeContainer.set` is total
     * for non-negative values, there is no failure path on the credit side
     * that would require a compensating refund.
     */
    override suspend fun transfer(
        from: PlayerId,
        to: PlayerId,
        currency: Currency,
        amount: Long,
    ): Result<TransferResult, WalletError> {
        require(amount >= 0) { "transfer amount must be >= 0, got $amount" }
        val fromWallet = walletFor(from)
        return when (val debit = fromWallet.withdraw(currency, amount)) {
            is Result.Failure -> Result.failure(debit.error)
            is Result.Success -> {
                val toWallet = walletFor(to).deposit(currency, amount)
                Result.success(TransferResult(fromWallet = fromWallet, toWallet = toWallet))
            }
        }
    }
}
