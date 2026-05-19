package com.astarworks.astera.economy.application.port.outbound

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.domain.policy.SourceClass
import java.time.Instant
import java.util.UUID

/**
 * Per-issuance ledger for JP regulatory compliance (RTM #19, ADR-0035).
 *
 * Every Deposit records an issuance row keyed by [IssuanceId]. The
 * [com.astarworks.astera.economy.application.usecase.policy.ExpireCurrencyUseCase]
 * scans rows where `issuedAt + policy.expirationDays < now` and removes them.
 * Sum of [IssuanceRecord.outstandingAmount] across rows = wallet balance for
 * that currency (modulo expiry).
 *
 * Production binding is Postgres-backed
 * ([com.astarworks.astera.persistence.repository.PostgresCurrencyIssuanceLog])
 * registered at priority=100 (Phase C wiring). The in-memory default lives in
 * the economy plugin for tests + dev (Phase C wiring).
 */
public interface ICurrencyIssuanceLog {
    public fun record(record: IssuanceRecord)
    public fun findExpired(currency: Currency, asOf: Instant, olderThanDays: Int): List<IssuanceRecord>
    public fun markConsumed(issuanceId: IssuanceId, amount: Long)
    public fun totalOutstanding(currency: Currency): Long
    public fun totalOutstanding(playerId: PlayerId, currency: Currency): Long
}

@JvmInline
public value class IssuanceId(public val value: UUID)

public data class IssuanceRecord(
    public val id: IssuanceId,
    public val playerId: PlayerId,
    public val currency: Currency,
    public val sourceClass: SourceClass,
    public val issuedAmount: Long,
    public val outstandingAmount: Long,
    public val issuedAt: Instant,
)
