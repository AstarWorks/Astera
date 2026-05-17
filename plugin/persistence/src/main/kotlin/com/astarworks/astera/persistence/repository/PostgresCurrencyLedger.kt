package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.TransferResult
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.economy.Wallet
import com.astarworks.astera.core.domain.economy.WalletError
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.table.WalletsTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/**
 * Postgres-backed [ICurrencyLedger]. Registered by AsteraPersistencePlugin at
 * priority=100, overlays the InMemory default registered by AsteraEconomyPlugin.
 *
 * Atomicity: every public mutation wraps its SQL in a single Exposed [transaction]
 * so debit/credit/transfer either commits whole or aborts whole. The default
 * isolation level is READ_COMMITTED — sufficient for Phase 1 because debit and
 * transfer use a conditional UPDATE (`balance >= amount`) that detects the
 * lost-update race and surfaces it as [WalletError.InsufficientFunds] instead
 * of corrupting the balance. If a stronger guarantee is ever needed (e.g.
 * read-your-writes across concurrent transfers between the same two accounts)
 * upgrade to SERIALIZABLE or `SELECT ... FOR UPDATE` — Phase 1 acceptable.
 */
public class PostgresCurrencyLedger(private val db: Database) : ICurrencyLedger {

    override fun balance(playerId: PlayerId, currency: Currency): Long = transaction(db) {
        readBalance(playerId, currency)
    }

    override fun wallet(playerId: PlayerId): Wallet = transaction(db) {
        readWallet(playerId)
    }

    override fun debit(
        playerId: PlayerId,
        currency: Currency,
        amount: Long,
    ): Result<Wallet, WalletError> {
        require(amount >= 0L) { "debit amount must be >= 0, got $amount" }
        if (amount == 0L) {
            return transaction(db) { Result.success(readWallet(playerId)) }
        }
        return transaction(db) {
            val rows = WalletsTable.update({
                (WalletsTable.playerId eq playerId.uuid) and
                    (WalletsTable.currency eq currency.name) and
                    (WalletsTable.balance greaterEq amount)
            }) {
                it[WalletsTable.balance] = WalletsTable.balance - amount
                it[WalletsTable.updatedAt] = Instant.now()
            }
            if (rows == 0) {
                val have = readBalance(playerId, currency)
                Result.failure(WalletError.InsufficientFunds(currency, have, amount))
            } else {
                Result.success(readWallet(playerId))
            }
        }
    }

    override fun credit(playerId: PlayerId, currency: Currency, amount: Long): Wallet {
        require(amount >= 0L) { "credit amount must be >= 0, got $amount" }
        if (amount == 0L) {
            return transaction(db) { readWallet(playerId) }
        }
        return transaction(db) {
            creditInPlace(playerId, currency, amount)
            readWallet(playerId)
        }
    }

    override fun transfer(
        from: PlayerId,
        to: PlayerId,
        currency: Currency,
        amount: Long,
    ): Result<TransferResult, WalletError> {
        require(amount >= 0L) { "transfer amount must be >= 0, got $amount" }
        if (amount == 0L) {
            return transaction(db) {
                Result.success(TransferResult(readWallet(from), readWallet(to)))
            }
        }
        return transaction(db) {
            // Conditional debit on `from`. If 0 rows are affected we know either
            // the wallet doesn't exist or the balance is below `amount` — both
            // surface as InsufficientFunds.
            val debited = WalletsTable.update({
                (WalletsTable.playerId eq from.uuid) and
                    (WalletsTable.currency eq currency.name) and
                    (WalletsTable.balance greaterEq amount)
            }) {
                it[WalletsTable.balance] = WalletsTable.balance - amount
                it[WalletsTable.updatedAt] = Instant.now()
            }
            if (debited == 0) {
                val have = readBalance(from, currency)
                return@transaction Result.failure(WalletError.InsufficientFunds(currency, have, amount))
            }
            // Credit `to` in the same transaction — atomic with the debit.
            creditInPlace(to, currency, amount)
            Result.success(TransferResult(fromWallet = readWallet(from), toWallet = readWallet(to)))
        }
    }

    // --- helpers (assume an active Exposed transaction) ---

    private fun readBalance(playerId: PlayerId, currency: Currency): Long =
        WalletsTable
            .selectAll()
            .where {
                (WalletsTable.playerId eq playerId.uuid) and
                    (WalletsTable.currency eq currency.name)
            }
            .firstOrNull()
            ?.get(WalletsTable.balance) ?: 0L

    private fun readWallet(playerId: PlayerId): Wallet {
        val balances = WalletsTable
            .selectAll()
            .where { WalletsTable.playerId eq playerId.uuid }
            .associate { row ->
                val name = row[WalletsTable.currency]
                val currency = runCatching { Currency.valueOf(name) }.getOrNull()
                currency to row[WalletsTable.balance]
            }
            .filterKeys { it != null }
            // Drop balances == 0 so an empty wallet round-trips to Wallet.EMPTY.
            .filterValues { it > 0L }
            .mapKeys { (key, _) -> key!! }
        return if (balances.isEmpty()) Wallet.EMPTY else Wallet(balances)
    }

    /**
     * Additive upsert on `(player_id, currency)`. Implemented as `UPDATE` first
     * (the common path: wallet already exists from a prior credit) and falls
     * back to `INSERT` when the row didn't exist. Both run inside the caller's
     * transaction so the pair is atomic.
     *
     * Note: under high contention two concurrent first-time credits to the
     * same (player, currency) could both observe `UPDATE` returning 0 and
     * race into `INSERT`. Postgres will reject the second insert on the
     * unique PK and the transaction retries — Exposed surfaces this as an
     * exception. Acceptable for Phase 1; switch to `INSERT ... ON CONFLICT
     * DO UPDATE SET balance = wallets.balance + EXCLUDED.balance` (raw SQL or
     * Exposed `upsert` with `onUpdate`) if it becomes a measurable hotspot.
     */
    private fun creditInPlace(playerId: PlayerId, currency: Currency, amount: Long) {
        val updated = WalletsTable.update({
            (WalletsTable.playerId eq playerId.uuid) and
                (WalletsTable.currency eq currency.name)
        }) {
            it[WalletsTable.balance] = WalletsTable.balance + amount
            it[WalletsTable.updatedAt] = Instant.now()
        }
        if (updated == 0) {
            WalletsTable.insert {
                it[WalletsTable.playerId] = playerId.uuid
                it[WalletsTable.currency] = currency.name
                it[WalletsTable.balance] = amount
                it[WalletsTable.updatedAt] = Instant.now()
            }
        }
    }
}
