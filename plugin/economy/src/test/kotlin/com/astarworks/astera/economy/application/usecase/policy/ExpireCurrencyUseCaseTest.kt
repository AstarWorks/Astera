package com.astarworks.astera.economy.application.usecase.policy

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.ICurrencyIssuanceLog
import com.astarworks.astera.economy.application.port.outbound.IssuanceId
import com.astarworks.astera.economy.application.port.outbound.IssuanceRecord
import com.astarworks.astera.economy.application.service.InMemoryCurrencyLedger
import com.astarworks.astera.economy.domain.event.CurrencyExpired
import com.astarworks.astera.economy.domain.policy.CurrencyPolicy
import com.astarworks.astera.economy.domain.policy.SourceClass
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class ExpireCurrencyUseCaseTest {

    private val playerId = PlayerId(UUID.randomUUID())
    private val now: Instant = Instant.parse("2026-05-19T00:00:00Z")

    @Test
    fun `180-day-old issuance gets expired`() {
        val ledger = InMemoryCurrencyLedger().also { it.credit(playerId, Currency.GEM, 500L) }
        val log = InMemoryIssuanceLog()
        val record = IssuanceRecord(
            id = IssuanceId(UUID.randomUUID()),
            playerId = playerId,
            currency = Currency.GEM,
            sourceClass = SourceClass.Purchased,
            issuedAmount = 500L,
            outstandingAmount = 500L,
            issuedAt = now.minus(180, ChronoUnit.DAYS).minus(1, ChronoUnit.HOURS),
        )
        log.record(record)

        val eventBus = FakeDomainEventBus()
        val scheduler = FakeScheduler(startTick = 1000L)
        val policy = jpPrepaidPolicy(Currency.GEM, days = 180)
        val uc = ExpireCurrencyUseCase(
            issuanceLog = log,
            ledger = ledger,
            eventBus = eventBus,
            scheduler = scheduler,
            policyOf = { policy },
            clock = { now },
        )

        uc.sweep()

        assertThat(ledger.balance(playerId, Currency.GEM)).isEqualTo(0L)
        assertThat(log.outstanding(record.id)).isEqualTo(0L)
        val events = eventBus.publishedOfType<CurrencyExpired>()
        assertThat(events).hasSize(1)
        val ev = events.single()
        assertThat(ev.playerId).isEqualTo(playerId)
        assertThat(ev.currency).isEqualTo(Currency.GEM)
        assertThat(ev.amount).isEqualTo(500L)
        assertThat(ev.expiredAt).isEqualTo(now)
        assertThat(ev.originalIssuedAt).isEqualTo(record.issuedAt)
        assertThat(ev.occurredAtTick).isEqualTo(1000L)
    }

    @Test
    fun `179-day-old issuance survives`() {
        val ledger = InMemoryCurrencyLedger().also { it.credit(playerId, Currency.GEM, 500L) }
        val log = InMemoryIssuanceLog()
        log.record(
            IssuanceRecord(
                id = IssuanceId(UUID.randomUUID()),
                playerId = playerId,
                currency = Currency.GEM,
                sourceClass = SourceClass.Purchased,
                issuedAmount = 500L,
                outstandingAmount = 500L,
                issuedAt = now.minus(179, ChronoUnit.DAYS),
            ),
        )

        val eventBus = FakeDomainEventBus()
        val scheduler = FakeScheduler()
        val policy = jpPrepaidPolicy(Currency.GEM, days = 180)
        val uc = ExpireCurrencyUseCase(
            issuanceLog = log,
            ledger = ledger,
            eventBus = eventBus,
            scheduler = scheduler,
            policyOf = { policy },
            clock = { now },
        )

        uc.sweep()

        assertThat(ledger.balance(playerId, Currency.GEM)).isEqualTo(500L)
        assertThat(eventBus.publishedOfType<CurrencyExpired>()).isEmpty()
    }

    @Test
    fun `non-expirable currency is never swept`() {
        val ledger = InMemoryCurrencyLedger().also { it.credit(playerId, Currency.GOLD, 500L) }
        val log = InMemoryIssuanceLog()
        log.record(
            IssuanceRecord(
                id = IssuanceId(UUID.randomUUID()),
                playerId = playerId,
                currency = Currency.GOLD,
                sourceClass = SourceClass.Earned,
                issuedAmount = 500L,
                outstandingAmount = 500L,
                issuedAt = now.minus(365 * 5L, ChronoUnit.DAYS), // 5 years old
            ),
        )

        val eventBus = FakeDomainEventBus()
        val scheduler = FakeScheduler()
        val nonExpirable = CurrencyPolicy(
            currency = Currency.GOLD,
            expirable = false,
            expirationDays = null,
            circulationCap = null,
            redeemable = false,
            tradable = true,
            sourceClass = SourceClass.Earned,
        )
        // Each Currency lookup needs to return *some* policy. Default non-expirable for all.
        val uc = ExpireCurrencyUseCase(
            issuanceLog = log,
            ledger = ledger,
            eventBus = eventBus,
            scheduler = scheduler,
            policyOf = { nonExpirable.copy(currency = it) },
            clock = { now },
        )

        uc.sweep()

        assertThat(ledger.balance(playerId, Currency.GOLD)).isEqualTo(500L)
        assertThat(eventBus.publishedOfType<CurrencyExpired>()).isEmpty()
    }

    @Test
    fun `CurrencyExpired event carries correct amount`() {
        val ledger = InMemoryCurrencyLedger().also { it.credit(playerId, Currency.GEM, 750L) }
        val log = InMemoryIssuanceLog()
        val record = IssuanceRecord(
            id = IssuanceId(UUID.randomUUID()),
            playerId = playerId,
            currency = Currency.GEM,
            sourceClass = SourceClass.Bonus,
            issuedAmount = 1000L,
            outstandingAmount = 750L, // partially consumed already
            issuedAt = now.minus(200, ChronoUnit.DAYS),
        )
        log.record(record)

        val eventBus = FakeDomainEventBus()
        val scheduler = FakeScheduler(startTick = 5_000L)
        val policy = jpPrepaidPolicy(Currency.GEM, days = 180)
        val uc = ExpireCurrencyUseCase(
            issuanceLog = log,
            ledger = ledger,
            eventBus = eventBus,
            scheduler = scheduler,
            policyOf = { policy },
            clock = { now },
        )

        uc.sweep()

        val ev = eventBus.publishedOfType<CurrencyExpired>().single()
        assertThat(ev.amount).isEqualTo(750L)
        assertThat(ev.occurredAtTick).isEqualTo(5_000L)
        assertThat(ledger.balance(playerId, Currency.GEM)).isEqualTo(0L)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun jpPrepaidPolicy(currency: Currency, days: Int): CurrencyPolicy = CurrencyPolicy(
        currency = currency,
        expirable = true,
        expirationDays = days,
        circulationCap = 10_000_000L,
        redeemable = false,
        tradable = false,
        sourceClass = SourceClass.Purchased,
    )
}

/**
 * In-test fake of [ICurrencyIssuanceLog]. `findExpired` matches on
 * `issuedAt < (asOf - olderThanDays)` and outstanding > 0, mirroring the
 * Postgres impl's WHERE clause.
 */
private class InMemoryIssuanceLog : ICurrencyIssuanceLog {

    private val records: MutableMap<IssuanceId, IssuanceRecord> = linkedMapOf()

    fun outstanding(id: IssuanceId): Long = records[id]?.outstandingAmount ?: 0L

    override fun record(record: IssuanceRecord) {
        records[record.id] = record
    }

    override fun findExpired(currency: Currency, asOf: Instant, olderThanDays: Int): List<IssuanceRecord> {
        val cutoff = asOf.minus(olderThanDays.toLong(), ChronoUnit.DAYS)
        return records.values.filter {
            it.currency == currency &&
                it.issuedAt.isBefore(cutoff) &&
                it.outstandingAmount > 0L
        }
    }

    override fun markConsumed(issuanceId: IssuanceId, amount: Long) {
        val existing = records[issuanceId] ?: return
        records[issuanceId] = existing.copy(
            outstandingAmount = (existing.outstandingAmount - amount).coerceAtLeast(0L),
        )
    }

    override fun totalOutstanding(currency: Currency): Long =
        records.values.filter { it.currency == currency }.sumOf { it.outstandingAmount }

    override fun totalOutstanding(playerId: PlayerId, currency: Currency): Long =
        records.values
            .filter { it.playerId == playerId && it.currency == currency }
            .sumOf { it.outstandingAmount }
}
