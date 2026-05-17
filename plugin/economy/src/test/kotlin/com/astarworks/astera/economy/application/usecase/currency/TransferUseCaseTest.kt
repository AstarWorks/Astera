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

class TransferUseCaseTest {

    private val ledger = InMemoryCurrencyLedger()
    private val eventBus = FakeDomainEventBus()
    private val uc = TransferUseCase(ledger, eventBus)

    private val alice = PlayerId(UUID.randomUUID())
    private val bob = PlayerId(UUID.randomUUID())

    private fun seed(playerId: PlayerId, amount: Long) {
        ledger.credit(playerId, Currency.GOLD, amount)
    }

    @Test
    fun `happy path transfers amount and publishes two CurrencyChanged events`() {
        seed(alice, 500L)
        val result = uc.execute(alice, bob, Currency.GOLD, 200L)

        assertThat(result.isSuccess).isTrue()
        assertThat(ledger.balance(alice, Currency.GOLD)).isEqualTo(300L)
        assertThat(ledger.balance(bob, Currency.GOLD)).isEqualTo(200L)

        val events = eventBus.publishedOfType<CurrencyChanged>()
        assertThat(events).hasSize(2)

        // First event: debit from alice
        val debitEv = events[0]
        assertThat(debitEv.playerId).isEqualTo(alice)
        assertThat(debitEv.delta).isEqualTo(-200L)
        assertThat(debitEv.newBalance).isEqualTo(300L)
        assertThat(debitEv.reason).isEqualTo(CurrencyChangeReason.Transfer(counterpartyId = bob))

        // Second event: credit to bob
        val creditEv = events[1]
        assertThat(creditEv.playerId).isEqualTo(bob)
        assertThat(creditEv.delta).isEqualTo(200L)
        assertThat(creditEv.newBalance).isEqualTo(200L)
        assertThat(creditEv.reason).isEqualTo(CurrencyChangeReason.Transfer(counterpartyId = alice))
    }

    @Test
    fun `amount zero yields InvalidAmount`() {
        seed(alice, 100L)
        val result = uc.execute(alice, bob, Currency.GOLD, 0L)

        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isEqualTo(TransferError.InvalidAmount(0L))
        assertThat(eventBus.published).isEmpty()
    }

    @Test
    fun `same account yields SameAccount error`() {
        seed(alice, 100L)
        val result = uc.execute(alice, alice, Currency.GOLD, 50L)

        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isEqualTo(TransferError.SameAccount(alice))
        assertThat(eventBus.published).isEmpty()
    }

    @Test
    fun `insufficient funds yields InsufficientFunds and no ledger mutation`() {
        seed(alice, 10L)
        val result = uc.execute(alice, bob, Currency.GOLD, 100L)

        assertThat(result.isFailure).isTrue()
        val err = result.errorOrNull()
        assertThat(err).isInstanceOf(TransferError.InsufficientFunds::class.java)
        val ifErr = err as TransferError.InsufficientFunds
        assertThat(ifErr.balance).isEqualTo(10L)
        assertThat(ifErr.requested).isEqualTo(100L)
        // Neither account mutated
        assertThat(ledger.balance(alice, Currency.GOLD)).isEqualTo(10L)
        assertThat(ledger.balance(bob, Currency.GOLD)).isEqualTo(0L)
        assertThat(eventBus.published).isEmpty()
    }
}
