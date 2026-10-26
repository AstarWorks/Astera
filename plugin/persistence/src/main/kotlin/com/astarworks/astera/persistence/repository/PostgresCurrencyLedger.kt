package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.TransferResult
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.economy.Wallet
import com.astarworks.astera.core.domain.economy.WalletError
import com.astarworks.astera.core.domain.player.PlayerId
import org.jetbrains.exposed.v1.jdbc.Database
import com.astarworks.astera.persistence.adapter.io.blockingTransaction
import com.astarworks.astera.persistence.adapter.io.IoThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.util.UUID

/**
 * Postgres-backed [ICurrencyLedger]: **every call is read-through and atomic
 * in the database**, so the lobby and any number of game backends can move the
 * same player's money at the same time (ADR-0201, supersedes the per-JVM lock
 * striping of ADR-0123 for wallets).
 *
 * Balances live in the `wallet.<currency>` entries of the player's
 * `player_attributes.payload` (V14), exactly where the attribute container
 * keeps them, so no data migration is needed. The ledger never goes through
 * the container cache (which is per JVM and can be stale after another
 * server wrote): it changes the one JSONB entry in a single statement
 *
 *  - `credit`: adds to the stored value (the row is created on first use);
 *  - `debit`: `UPDATE ... WHERE stored >= amount RETURNING` - the row lock
 *    serialises concurrent spenders on any server, the loser gets
 *    [WalletError.InsufficientFunds];
 *  - `transfer`: one transaction, both rows locked in uuid order, so two
 *    opposite transfers cannot deadlock.
 *
 * [PostgresPlayerAttributeContainer] cooperates: when it writes a player's
 * document back it keeps the wallet entries that are already in the database
 * rather than its (possibly stale) cached ones, so an XP or stat write on one
 * server can no longer roll back money earned on another.
 */
public class PostgresCurrencyLedger(private val db: Database) : ICurrencyLedger {

    private val balances = java.util.concurrent.ConcurrentHashMap<PlayerId, Map<Currency, Long>>()

    override fun balance(playerId: PlayerId, currency: Currency): Long =
        checkNotNull(balances[playerId]) { "Wallet not preloaded: $playerId" }.getValue(currency)

    public fun preload(playerId: PlayerId) {
        balances[playerId] = withConnection { c -> Currency.entries.associateWith { readBalance(c, playerId.uuid, it) } }
    }

    public fun evict(playerId: PlayerId) { balances.remove(playerId) }

    /**
     * Detached snapshot of the stored balances (fresh at call time). Mutate
     * through [credit] / [debit]; changes to the snapshot are not written back.
     */
    override fun wallet(playerId: PlayerId): Wallet {
        val snapshot = SimpleAttributeContainer()
        Currency.entries.forEach { currency ->
            snapshot.set(Wallet.walletKeyFor(currency), AttributeValue.NumberValue(balance(playerId, currency).toDouble()))
        }
        return Wallet(snapshot)
    }

    override suspend fun debit(playerId: PlayerId, currency: Currency, amount: Long): Result<Wallet, WalletError> =
        withContext(Dispatchers.IO) { IoThread.run { debitOnIo(playerId, currency, amount) } }

    override suspend fun credit(playerId: PlayerId, currency: Currency, amount: Long): Wallet =
        withContext(Dispatchers.IO) { IoThread.run { creditOnIo(playerId, currency, amount) } }

    override suspend fun transfer(from: PlayerId, to: PlayerId, currency: Currency, amount: Long): Result<TransferResult, WalletError> =
        withContext(Dispatchers.IO) { IoThread.run { transferOnIo(from, to, currency, amount) } }

    internal fun debitOnIo(
        playerId: PlayerId,
        currency: Currency,
        amount: Long,
    ): Result<Wallet, WalletError> {
        require(amount >= 0L) { "debit amount must be >= 0, got $amount" }
        val outcome = withConnection { c -> debitOn(c, playerId.uuid, currency, amount) }
        preload(playerId)
        return if (outcome == null) {
            Result.failure(WalletError.InsufficientFunds(currency, balance(playerId, currency), amount))
        } else {
            Result.success(wallet(playerId))
        }
    }

    internal fun creditOnIo(playerId: PlayerId, currency: Currency, amount: Long): Wallet {
        require(amount >= 0L) { "credit amount must be >= 0, got $amount" }
        withConnection { c -> creditOn(c, playerId.uuid, currency, amount) }
        preload(playerId)
        return wallet(playerId)
    }

    internal fun transferOnIo(
        from: PlayerId,
        to: PlayerId,
        currency: Currency,
        amount: Long,
    ): Result<TransferResult, WalletError> {
        require(amount >= 0L) { "transfer amount must be >= 0, got $amount" }
        val ok = withConnection { c ->
            // Make sure both rows exist, then lock them in a fixed order.
            listOf(from.uuid, to.uuid).forEach { ensureRow(c, it) }
            listOf(from.uuid, to.uuid).sorted().forEach { id ->
                c.prepareStatement("SELECT 1 FROM player_attributes WHERE player_id = ? FOR UPDATE").use { st ->
                    st.setObject(1, id)
                    st.executeQuery().use { it.next() }
                }
            }
            if (debitOn(c, from.uuid, currency, amount) == null) {
                false
            } else {
                creditOn(c, to.uuid, currency, amount)
                true
            }
        }
        preload(from)
        preload(to)
        return if (ok) {
            Result.success(TransferResult(fromWallet = wallet(from), toWallet = wallet(to)))
        } else {
            Result.failure(WalletError.InsufficientFunds(currency, balance(from, currency), amount))
        }
    }

    // ---- SQL ---------------------------------------------------------------------

    private fun key(currency: Currency): String = Wallet.walletKeyFor(currency).namespaced

    private fun readBalance(c: Connection, id: UUID, currency: Currency): Long =
        c.prepareStatement(
            "SELECT COALESCE((payload #>> ARRAY['values', ?, 'value'])::numeric, 0)::bigint " +
                "FROM player_attributes WHERE player_id = ?",
        ).use { st ->
            st.setString(1, key(currency))
            st.setObject(2, id)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1).coerceAtLeast(0L) else 0L }
        }

    private fun ensureRow(c: Connection, id: UUID) {
        c.prepareStatement("INSERT INTO player_attributes (player_id) VALUES (?) ON CONFLICT (player_id) DO NOTHING")
            .use { st ->
                st.setObject(1, id)
                st.executeUpdate()
            }
    }

    /** Adds [amount]; the row is created on first use. Returns the new balance. */
    private fun creditOn(c: Connection, id: UUID, currency: Currency, amount: Long): Long {
        ensureRow(c, id)
        return c.prepareStatement(
            "UPDATE player_attributes SET " +
                "payload = jsonb_set(payload, ARRAY['values', ?], " +
                "jsonb_build_object('type', 'number', 'value', " +
                "COALESCE((payload #>> ARRAY['values', ?, 'value'])::numeric, 0) + ?), true), " +
                "updated_at = now() WHERE player_id = ? " +
                "RETURNING (payload #>> ARRAY['values', ?, 'value'])::numeric::bigint",
        ).use { st ->
            val k = key(currency)
            st.setString(1, k)
            st.setString(2, k)
            st.setLong(3, amount)
            st.setObject(4, id)
            st.setString(5, k)
            st.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    /** Subtracts [amount] if the stored balance covers it; returns the new balance, or null when it does not. */
    private fun debitOn(c: Connection, id: UUID, currency: Currency, amount: Long): Long? =
        c.prepareStatement(
            "UPDATE player_attributes SET " +
                "payload = jsonb_set(payload, ARRAY['values', ?], " +
                "jsonb_build_object('type', 'number', 'value', " +
                "COALESCE((payload #>> ARRAY['values', ?, 'value'])::numeric, 0) - ?), true), " +
                "updated_at = now() WHERE player_id = ? " +
                "AND COALESCE((payload #>> ARRAY['values', ?, 'value'])::numeric, 0) >= ? " +
                "RETURNING (payload #>> ARRAY['values', ?, 'value'])::numeric::bigint",
        ).use { st ->
            val k = key(currency)
            st.setString(1, k)
            st.setString(2, k)
            st.setLong(3, amount)
            st.setObject(4, id)
            st.setString(5, k)
            st.setLong(6, amount)
            st.setString(7, k)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    private fun <T> withConnection(block: (Connection) -> T): T = blockingTransaction(db) {
        block(connection.connection as Connection)
    }
}
