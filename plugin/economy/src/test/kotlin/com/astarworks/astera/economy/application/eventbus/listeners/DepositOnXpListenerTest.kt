package com.astarworks.astera.economy.application.eventbus.listeners

import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.service.InMemoryCurrencyLedger
import com.astarworks.astera.economy.application.usecase.currency.DepositUseCase
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.stats.domain.event.XpGranted
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class DepositOnXpListenerTest {

    private val ledger = InMemoryCurrencyLedger()
    private val eventBus = FakeDomainEventBus()
    private val depositUc = DepositUseCase(ledger, eventBus)
    private val listener = DepositOnXpListener(depositUc)

    private val playerId = PlayerId(UUID.randomUUID())

    @Test
    fun `XpGranted with amount 50 grants 5 STAR and publishes one CurrencyChanged`() {
        listener.handle(
            XpGranted(
                playerId = playerId,
                amount = 50L,
                newTotal = 50L,
                newLevel = null,
                source = XpSource.Kill,
            ),
        )

        assertThat(ledger.balance(playerId, Currency.STAR)).isEqualTo(5L)

        val events = eventBus.publishedOfType<CurrencyChanged>()
        assertThat(events).hasSize(1)
        val ev = events.single()
        assertThat(ev.playerId).isEqualTo(playerId)
        assertThat(ev.currency).isEqualTo(Currency.STAR)
        assertThat(ev.delta).isEqualTo(5L)
        assertThat(ev.reason).isEqualTo(CurrencyChangeReason.MissionReward)
    }

    @Test
    fun `XpGranted with small amount (5) grants at least 1 STAR via coerceAtLeast`() {
        listener.handle(
            XpGranted(
                playerId = playerId,
                amount = 5L,
                newTotal = 5L,
                newLevel = null,
                source = XpSource.Kill,
            ),
        )

        // max(1, 5/10=0) = 1
        assertThat(ledger.balance(playerId, Currency.STAR)).isEqualTo(1L)

        val events = eventBus.publishedOfType<CurrencyChanged>()
        assertThat(events).hasSize(1)
        assertThat(events.single().delta).isEqualTo(1L)
    }
}
