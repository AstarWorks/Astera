package com.astarworks.astera.economy.application.usecase.currency

import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.service.InMemoryCurrencyLedger
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.economy.domain.event.CurrencyChanged
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WithdrawUseCaseTest {

    private val ledger = InMemoryCurrencyLedger()
    private val eventBus = FakeDomainEventBus()
    private val uc = WithdrawUseCase(ledger, eventBus)

    private val playerId = PlayerId(UUID.randomUUID())

    private fun seed(amount: Long) {
        ledger.credit(playerId, Currency.STAR, amount)
    }

    @Test
    fun `happy path debits wallet and publishes CurrencyChanged with negative delta`() {
        seed(200L)
        val result = uc.execute(playerId, Currency.STAR, 50L, CurrencyChangeReason.Withdraw)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()!!.balance(Currency.STAR)).isEqualTo(150L)
        assertThat(ledger.balance(playerId, Currency.STAR)).isEqualTo(150L)

        val events = eventBus.publishedOfType<CurrencyChanged>()
        assertThat(events).hasSize(1)
        val ev = events.single()
        assertThat(ev.delta).isEqualTo(-50L)
        assertThat(ev.newBalance).isEqualTo(150L)
        assertThat(ev.reason).isEqualTo(CurrencyChangeReason.Withdraw)
    }

    @Test
    fun `amount zero yields InvalidAmount and no ledger mutation`() {
        seed(100L)
        val result = uc.execute(playerId, Currency.STAR, 0L, CurrencyChangeReason.Withdraw)

        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isEqualTo(WithdrawError.InvalidAmount(0L))
        assertThat(ledger.balance(playerId, Currency.STAR)).isEqualTo(100L)
        assertThat(eventBus.published).isEmpty()
    }

    @Test
    fun `insufficient funds yields InsufficientFunds and no ledger mutation`() {
        seed(30L)
        val result = uc.execute(playerId, Currency.STAR, 100L, CurrencyChangeReason.Withdraw)

        assertThat(result.isFailure).isTrue()
        val err = result.errorOrNull()
        assertThat(err).isInstanceOf(WithdrawError.InsufficientFunds::class.java)
        val ifErr = err as WithdrawError.InsufficientFunds
        assertThat(ifErr.balance).isEqualTo(30L)
        assertThat(ifErr.requested).isEqualTo(100L)
        assertThat(ifErr.currency).isEqualTo(Currency.STAR)
        // Balance unchanged
        assertThat(ledger.balance(playerId, Currency.STAR)).isEqualTo(30L)
        assertThat(eventBus.published).isEmpty()
    }
}
