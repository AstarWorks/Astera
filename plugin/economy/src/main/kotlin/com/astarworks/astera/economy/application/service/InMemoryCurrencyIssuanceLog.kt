package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.ICurrencyIssuanceLog
import com.astarworks.astera.economy.application.port.outbound.IssuanceId
import com.astarworks.astera.economy.application.port.outbound.IssuanceRecord
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory default for [ICurrencyIssuanceLog]. Wave 5f (ADR-0035) Phase 1
 * fallback when Postgres isn't loaded. `AsteraPersistencePlugin` overlays
 * `PostgresCurrencyIssuanceLog` at priority 100.
 *
 * Records are append-mutated via [markConsumed]; both totalOutstanding
 * variants compute on-the-fly from the live map (cardinality is bounded by
 * the active player population × #currencies).
 */
public class InMemoryCurrencyIssuanceLog : ICurrencyIssuanceLog {

    private val store = ConcurrentHashMap<IssuanceId, IssuanceRecord>()

    override fun record(record: IssuanceRecord) {
        store[record.id] = record
    }

    override fun findExpired(currency: Currency, asOf: Instant, olderThanDays: Int): List<IssuanceRecord> {
        val cutoff = asOf.minus(olderThanDays.toLong(), ChronoUnit.DAYS)
        return store.values.filter {
            it.currency == currency &&
                it.outstandingAmount > 0L &&
                it.issuedAt.isBefore(cutoff)
        }
    }

    override fun markConsumed(issuanceId: IssuanceId, amount: Long) {
        store.computeIfPresent(issuanceId) { _, record ->
            val newOutstanding = (record.outstandingAmount - amount).coerceAtLeast(0L)
            record.copy(outstandingAmount = newOutstanding)
        }
    }

    override fun totalOutstanding(currency: Currency): Long =
        store.values.filter { it.currency == currency }.sumOf { it.outstandingAmount }

    override fun totalOutstanding(playerId: PlayerId, currency: Currency): Long =
        store.values
            .filter { it.playerId == playerId && it.currency == currency }
            .sumOf { it.outstandingAmount }
}
