package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.TransferResult
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.economy.Wallet
import com.astarworks.astera.core.domain.economy.WalletError
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * Postgres-backed [ICurrencyLedger]. Wave 5e (ADR-0058 §5) shrank this to a
 * thin facade over [IPlayerAttributeContainerStore]:
 *
 * - The container store (PostgresPlayerAttributeContainer at priority 100)
 *   owns the V14 `player_attributes` JSONB row.
 * - This class wraps `containerFor(playerId)` with the [Wallet] facade so the
 *   ICurrencyLedger contract still composes through the same domain type.
 *
 * # Wave 5server-harden (ADR-0123): atomic debit guarantee
 *
 * Per-player lock striping (single [java.util.concurrent.ConcurrentHashMap]
 * of `Any` monitors) serialises every read-then-write op on the same player
 * so concurrent `debit` from two threads can no longer double-spend a single
 * balance — the loser sees [WalletError.InsufficientFunds]. Lock striping
 * sits at the ledger layer (not the container layer) because:
 *
 *  1. The race lives in the `balance → set` window inside `Wallet.withdraw`,
 *     which the ledger composes per-call.
 *  2. The container is shared with non-currency attributes (level, mana,
 *     skill xp) where wider locking would unnecessarily serialise unrelated
 *     reads.
 *  3. Postgres `SERIALIZABLE` would also fix the race, but in Phase 1
 *     Astera runs single-server so a JVM-local monitor is sufficient and
 *     observable in tests without leaning on Testcontainers-side isolation
 *     levels.
 *
 * Phase 6+ multi-server: the monitor approach must be replaced with
 * `SELECT ... FOR UPDATE` (or an advisory lock) — tracked as a follow-up
 * ADR.
 */
public class PostgresCurrencyLedger(
    private val containerStore: IPlayerAttributeContainerStore,
) : ICurrencyLedger {

    private val playerLocks = ConcurrentHashMap<PlayerId, Any>()

    private fun lockFor(playerId: PlayerId): Any =
        playerLocks.computeIfAbsent(playerId) { Any() }

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
        return synchronized(lockFor(playerId)) {
            walletFor(playerId).withdraw(currency, amount)
        }
    }

    override fun credit(playerId: PlayerId, currency: Currency, amount: Long): Wallet {
        require(amount >= 0L) { "credit amount must be >= 0, got $amount" }
        return synchronized(lockFor(playerId)) {
            walletFor(playerId).deposit(currency, amount)
        }
    }

    override fun transfer(
        from: PlayerId,
        to: PlayerId,
        currency: Currency,
        amount: Long,
    ): Result<TransferResult, WalletError> {
        require(amount >= 0L) { "transfer amount must be >= 0, got $amount" }
        // Acquire the two locks in id-order so two transfers in opposite
        // directions on the same pair cannot deadlock.
        val first = lockFor(from)
        val second = lockFor(to)
        val (a, b) = if (System.identityHashCode(first) <= System.identityHashCode(second)) {
            first to second
        } else {
            second to first
        }
        return synchronized(a) {
            synchronized(b) {
                val fromWallet = walletFor(from)
                when (val debit = fromWallet.withdraw(currency, amount)) {
                    is Result.Failure -> Result.failure(debit.error)
                    is Result.Success -> {
                        val toWallet = walletFor(to).deposit(currency, amount)
                        Result.success(TransferResult(fromWallet = fromWallet, toWallet = toWallet))
                    }
                }
            }
        }
    }
}
