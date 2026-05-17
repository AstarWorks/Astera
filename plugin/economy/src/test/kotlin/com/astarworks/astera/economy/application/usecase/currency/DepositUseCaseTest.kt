package com.astarworks.astera.economy.application.usecase.currency

import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.service.InMemoryCurrencyLedger
import com.astarworks.astera.economy.domain.economy.Currency
import com.astarworks.astera.economy.domain.event.CurrencyChanged
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class DepositUseCaseTest {

    private val ledger = InMemoryCurrencyLedger()
    private val eventBus = FakeDomainEventBus()
    private val uc = DepositUseCase(ledger, eventBus)

    private val playerId = PlayerId(UUID.randomUUID())

    @Test
    fun `happy path credits wallet and publishes CurrencyChanged`() {
        val result = uc.execute(playerId, Currency.STAR, 100L, CurrencyChangeReason.Deposit)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()!!.balance(Currency.STAR)).isEqualTo(100L)
        assertThat(ledger.balance(playerId, Currency.STAR)).isEqualTo(100L)

        val events = eventBus.publishedOfType<CurrencyChanged>()
        assertThat(events).hasSize(1)
        val ev = events.single()
        assertThat(ev.playerId).isEqualTo(playerId)
        assertThat(ev.currency).isEqualTo(Currency.STAR)
        assertThat(ev.delta).isEqualTo(100L)
        assertThat(ev.newBalance).isEqualTo(100L)
        assertThat(ev.reason).isEqualTo(CurrencyChangeReason.Deposit)
    }

    @Test
    fun `amount zero yields InvalidAmount and no ledger mutation`() {
        val result = uc.execute(playerId, Currency.GOLD, 0L, CurrencyChangeReason.Deposit)

        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isEqualTo(DepositError.InvalidAmount(0L))
        assertThat(ledger.balance(playerId, Currency.GOLD)).isEqualTo(0L)
        assertThat(eventBus.published).isEmpty()
    }

    @Test
    fun `negative amount yields InvalidAmount and no ledger mutation`() {
        val result = uc.execute(playerId, Currency.GEM, -5L, CurrencyChangeReason.AdminGrant)

        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isEqualTo(DepositError.InvalidAmount(-5L))
        assertThat(ledger.balance(playerId, Currency.GEM)).isEqualTo(0L)
        assertThat(eventBus.published).isEmpty()
    }
}
