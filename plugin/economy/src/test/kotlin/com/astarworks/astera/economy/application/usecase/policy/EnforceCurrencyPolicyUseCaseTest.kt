package com.astarworks.astera.economy.application.usecase.policy

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.ICurrencyIssuanceLog
import com.astarworks.astera.economy.application.port.outbound.IssuanceId
import com.astarworks.astera.economy.application.port.outbound.IssuanceRecord
import com.astarworks.astera.economy.domain.policy.CurrencyPolicy
import com.astarworks.astera.economy.domain.policy.SourceClass
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class EnforceCurrencyPolicyUseCaseTest {

    private val playerA = PlayerId(UUID.randomUUID())
    private val playerB = PlayerId(UUID.randomUUID())

    @Test
    fun `checkTransfer blocks when tradable=false`() {
        val policy = jpPrepaidPolicy(Currency.GEM, tradable = false)
        val uc = EnforceCurrencyPolicyUseCase(policyOf = { policy }, issuanceLog = FakeIssuanceLog())
        val result = uc.checkTransfer(Currency.GEM, playerA, playerB, 100L)
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull())
            .isEqualTo(CurrencyPolicyError.TransferBlocked(Currency.GEM))
    }

    @Test
    fun `checkTransfer succeeds when tradable=true`() {
        val policy = looseEarnedPolicy(Currency.GOLD, tradable = true)
        val uc = EnforceCurrencyPolicyUseCase(policyOf = { policy }, issuanceLog = FakeIssuanceLog())
        val result = uc.checkTransfer(Currency.GOLD, playerA, playerB, 100L)
        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `checkDeposit blocks when circulationCap would be exceeded`() {
        val log = FakeIssuanceLog().also { it.setOutstanding(Currency.GEM, 9_999_900L) }
        val policy = jpPrepaidPolicy(Currency.GEM, capYen = 10_000_000L)
        val uc = EnforceCurrencyPolicyUseCase(policyOf = { policy }, issuanceLog = log)

        val result = uc.checkDeposit(Currency.GEM, playerA, amount = 200L)

        assertThat(result.isFailure).isTrue()
        val err = result.errorOrNull() as CurrencyPolicyError.CapExceeded
        assertThat(err.currency).isEqualTo(Currency.GEM)
        assertThat(err.cap).isEqualTo(10_000_000L)
        assertThat(err.attempted).isEqualTo(10_000_100L)
    }

    @Test
    fun `checkDeposit succeeds when cap is null`() {
        val policy = looseEarnedPolicy(Currency.GOLD, tradable = true)
        val log = FakeIssuanceLog().also { it.setOutstanding(Currency.GOLD, 1_000_000_000L) }
        val uc = EnforceCurrencyPolicyUseCase(policyOf = { policy }, issuanceLog = log)

        val result = uc.checkDeposit(Currency.GOLD, playerA, amount = 50L)

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `checkDeposit succeeds when cap not yet hit`() {
        val log = FakeIssuanceLog().also { it.setOutstanding(Currency.GEM, 5_000_000L) }
        val policy = jpPrepaidPolicy(Currency.GEM, capYen = 10_000_000L)
        val uc = EnforceCurrencyPolicyUseCase(policyOf = { policy }, issuanceLog = log)

        val result = uc.checkDeposit(Currency.GEM, playerA, amount = 100L)

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `checkRedeem blocks when redeemable=false`() {
        val policy = jpPrepaidPolicy(Currency.GEM, tradable = false)
        val uc = EnforceCurrencyPolicyUseCase(policyOf = { policy }, issuanceLog = FakeIssuanceLog())

        val result = uc.checkRedeem(Currency.GEM)

        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isEqualTo(CurrencyPolicyError.RedeemBlocked(Currency.GEM))
    }

    @Test
    fun `checkRedeem succeeds when redeemable=true`() {
        // Hypothetical (non-JP) redeemable currency variant.
        val policy = CurrencyPolicy(
            currency = Currency.GOLD,
            expirable = false,
            expirationDays = null,
            circulationCap = null,
            redeemable = true,
            tradable = true,
            sourceClass = SourceClass.Earned,
        )
        val uc = EnforceCurrencyPolicyUseCase(policyOf = { policy }, issuanceLog = FakeIssuanceLog())

        val result = uc.checkRedeem(Currency.GOLD)

        assertThat(result.isSuccess).isTrue()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun jpPrepaidPolicy(
        currency: Currency,
        capYen: Long = 10_000_000L,
        tradable: Boolean = false,
    ): CurrencyPolicy = CurrencyPolicy(
        currency = currency,
        expirable = true,
        expirationDays = 180,
        circulationCap = capYen,
        redeemable = false,
        tradable = tradable,
        sourceClass = SourceClass.Purchased,
    )

    private fun looseEarnedPolicy(currency: Currency, tradable: Boolean): CurrencyPolicy = CurrencyPolicy(
        currency = currency,
        expirable = false,
        expirationDays = null,
        circulationCap = null,
        redeemable = false,
        tradable = tradable,
        sourceClass = SourceClass.Earned,
    )
}

/**
 * In-test fake of [ICurrencyIssuanceLog]. Records are kept in a list; the
 * `totalOutstanding` family is overridable by [setOutstanding] so cap-related
 * branches can be exercised without building real issuance fixtures.
 */
private class FakeIssuanceLog : ICurrencyIssuanceLog {
    private val records: MutableList<IssuanceRecord> = mutableListOf()
    private val outstandingOverride: MutableMap<Currency, Long> = mutableMapOf()

    fun setOutstanding(currency: Currency, value: Long) {
        outstandingOverride[currency] = value
    }

    override fun record(record: IssuanceRecord) {
        records += record
    }

    override fun findExpired(currency: Currency, asOf: Instant, olderThanDays: Int): List<IssuanceRecord> =
        emptyList()

    override fun markConsumed(issuanceId: IssuanceId, amount: Long) {
        // no-op
    }

    override fun totalOutstanding(currency: Currency): Long =
        outstandingOverride[currency] ?: records.filter { it.currency == currency }.sumOf { it.outstandingAmount }

    override fun totalOutstanding(playerId: PlayerId, currency: Currency): Long =
        records.filter { it.playerId == playerId && it.currency == currency }.sumOf { it.outstandingAmount }
}
