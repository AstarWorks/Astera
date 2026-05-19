package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.TransferResult
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.economy.Wallet
import com.astarworks.astera.core.domain.economy.WalletError
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Postgres-backed [ICurrencyLedger]. Wave 5e (ADR-0058 §5) shrank this to a
 * thin facade over [IPlayerAttributeContainerStore]:
 *
 * - The container store (PostgresPlayerAttributeContainer at priority 100)
 *   owns the V14 `player_attributes` JSONB row.
 * - This class wraps `containerFor(playerId)` with the [Wallet] facade so the
 *   ICurrencyLedger contract still composes through the same domain type.
 * - Atomicity: per-call container writes are upserts into the JSONB row;
 *   each `deposit`/`withdraw` is its own transaction inside the container
 *   write path. Transfer's two-step is debit-then-credit — same shape as
 *   the in-memory version, and good enough for Phase 1 (insufficient-funds
 *   on debit short-circuits the credit).
 *
 * If the legacy V3 `wallets` table is ever read from again, that's a sign
 * something missed the Wave 5e migration — file an ADR-0058 §5 superseder.
 */
public class PostgresCurrencyLedger(
    private val containerStore: IPlayerAttributeContainerStore,
) : ICurrencyLedger {

    private fun walletFor(playerId: PlayerId): Wallet =
        Wallet(containerStore.containerFor(playerId))

    override fun balance(playerId: PlayerId, currency: Currency): Long =
        walletFor(playerId).balance(currency)

    override fun wallet(playerId: PlayerId): Wallet = walletFor(playerId)

    override fun debit(
        playerId: PlayerId,
        currency: Currency,
        amount: Long,
    ): Result<Wallet, WalletError> {
        require(amount >= 0L) { "debit amount must be >= 0, got $amount" }
        return walletFor(playerId).withdraw(currency, amount)
    }

    override fun credit(playerId: PlayerId, currency: Currency, amount: Long): Wallet {
        require(amount >= 0L) { "credit amount must be >= 0, got $amount" }
        return walletFor(playerId).deposit(currency, amount)
    }

    override fun transfer(
        from: PlayerId,
        to: PlayerId,
        currency: Currency,
        amount: Long,
    ): Result<TransferResult, WalletError> {
        require(amount >= 0L) { "transfer amount must be >= 0, got $amount" }
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
