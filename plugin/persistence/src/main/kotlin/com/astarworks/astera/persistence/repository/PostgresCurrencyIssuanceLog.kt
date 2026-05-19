package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.ICurrencyIssuanceLog
import com.astarworks.astera.economy.application.port.outbound.IssuanceId
import com.astarworks.astera.economy.application.port.outbound.IssuanceRecord
import com.astarworks.astera.economy.domain.policy.SourceClass
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * `currency_issuance_log` Exposed table (Wave 5f / V11). One row per Deposit
 * issuance. Sum of [outstandingAmount] across rows for a `(player_id, currency)`
 * pair = wallet balance for that currency (modulo expiry).
 *
 * Persistence registers [PostgresCurrencyIssuanceLog] from
 * `AsteraPersistencePlugin` at priority=100 (Phase C wiring) overlaying the
 * in-memory default registered by the economy plugin.
 */
public object CurrencyIssuanceLogTable : Table("currency_issuance_log") {
    public val id = javaUUID("id")
    public val playerId = javaUUID("player_id")
    public val currency = varchar("currency", 16)
    public val sourceClass = varchar("source_class", 16)
    public val issuedAmount = long("issued_amount")
    public val outstandingAmount = long("outstanding_amount")
    public val issuedAt = timestamp("issued_at")
    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

/**
 * Postgres-backed [ICurrencyIssuanceLog] (Wave 5f / ADR-0035, RTM #19 JP
 * 資金決済法 compliance).
 *
 * Storage model: append-only on Deposit (`record`), in-place mutation of
 * `outstanding_amount` on expiry (`markConsumed`). Rows are kept after
 * `outstanding_amount = 0` for audit; admin tooling may prune in batches
 * (separate Phase 4 task).
 *
 * Sums are computed via `selectAll().sumOf { ... }` in-Kotlin rather than a
 * SQL aggregate. The cardinality is bounded by `(players × currencies ×
 * ~10 deposits/month/currency × 6 months)` ≈ a few thousand rows per
 * currency, which fits comfortably in memory. If volumes grow, swap to a
 * SQL `SUM(outstanding_amount)` aggregate without changing the port.
 */
public class PostgresCurrencyIssuanceLog(private val db: Database) : ICurrencyIssuanceLog {

    override fun record(record: IssuanceRecord): Unit = transaction(db) {
        CurrencyIssuanceLogTable.insert {
            it[id] = record.id.value
            it[playerId] = record.playerId.uuid
            it[currency] = record.currency.name
            it[sourceClass] = record.sourceClass.discriminator()
            it[issuedAmount] = record.issuedAmount
            it[outstandingAmount] = record.outstandingAmount
            it[issuedAt] = record.issuedAt
        }
        Unit
    }

    override fun findExpired(currency: Currency, asOf: Instant, olderThanDays: Int): List<IssuanceRecord> {
        require(olderThanDays > 0) { "olderThanDays must be > 0, got $olderThanDays" }
        val cutoff = asOf.minus(olderThanDays.toLong(), ChronoUnit.DAYS)
        return transaction(db) {
            CurrencyIssuanceLogTable
                .selectAll()
                .where {
                    (CurrencyIssuanceLogTable.currency eq currency.name) and
                        (CurrencyIssuanceLogTable.issuedAt less cutoff) and
                        (CurrencyIssuanceLogTable.outstandingAmount neq 0L)
                }
                .map { it.toDomain() }
        }
    }

    override fun markConsumed(issuanceId: IssuanceId, amount: Long) {
        require(amount >= 0) { "amount must be >= 0, got $amount" }
        if (amount == 0L) return
        transaction(db) {
            val current = CurrencyIssuanceLogTable
                .selectAll()
                .where { CurrencyIssuanceLogTable.id eq issuanceId.value }
                .firstOrNull()
                ?: return@transaction
            val newOutstanding = (current[CurrencyIssuanceLogTable.outstandingAmount] - amount).coerceAtLeast(0L)
            CurrencyIssuanceLogTable.update({ CurrencyIssuanceLogTable.id eq issuanceId.value }) {
                it[outstandingAmount] = newOutstanding
            }
        }
    }

    override fun totalOutstanding(currency: Currency): Long = transaction(db) {
        CurrencyIssuanceLogTable
            .selectAll()
            .where { CurrencyIssuanceLogTable.currency eq currency.name }
            .sumOf { it[CurrencyIssuanceLogTable.outstandingAmount] }
    }

    override fun totalOutstanding(playerId: PlayerId, currency: Currency): Long = transaction(db) {
        CurrencyIssuanceLogTable
            .selectAll()
            .where {
                (CurrencyIssuanceLogTable.playerId eq playerId.uuid) and
                    (CurrencyIssuanceLogTable.currency eq currency.name)
            }
            .sumOf { it[CurrencyIssuanceLogTable.outstandingAmount] }
    }

    private fun ResultRow.toDomain(): IssuanceRecord = IssuanceRecord(
        id = IssuanceId(this[CurrencyIssuanceLogTable.id]),
        playerId = PlayerId(this[CurrencyIssuanceLogTable.playerId]),
        currency = Currency.valueOf(this[CurrencyIssuanceLogTable.currency]),
        sourceClass = parseSourceClass(this[CurrencyIssuanceLogTable.sourceClass]),
        issuedAmount = this[CurrencyIssuanceLogTable.issuedAmount],
        outstandingAmount = this[CurrencyIssuanceLogTable.outstandingAmount],
        issuedAt = this[CurrencyIssuanceLogTable.issuedAt],
    )

    private fun SourceClass.discriminator(): String = when (this) {
        is SourceClass.Purchased -> "purchased"
        is SourceClass.Earned -> "earned"
        is SourceClass.Bonus -> "bonus"
    }

    private fun parseSourceClass(raw: String): SourceClass = when (raw) {
        "purchased" -> SourceClass.Purchased
        "earned" -> SourceClass.Earned
        "bonus" -> SourceClass.Bonus
        else -> error("PostgresCurrencyIssuanceLog: unknown source_class='$raw' (forward-compat fence)")
    }
}
