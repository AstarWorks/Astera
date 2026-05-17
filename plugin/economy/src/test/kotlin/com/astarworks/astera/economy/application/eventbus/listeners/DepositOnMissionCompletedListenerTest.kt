package com.astarworks.astera.economy.application.eventbus.listeners

import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.service.InMemoryCurrencyLedger
import com.astarworks.astera.economy.application.usecase.currency.DepositUseCase
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.economy.domain.event.CurrencyChanged
import com.astarworks.astera.stats.domain.event.MissionCompleted
import com.astarworks.astera.stats.domain.norma.NormaId
import com.astarworks.astera.stats.domain.norma.NormaReward
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class DepositOnMissionCompletedListenerTest {

    private lateinit var ledger: InMemoryCurrencyLedger
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var listener: DepositOnMissionCompletedListener

    private val playerId = PlayerId(UUID.randomUUID())
    private val normaId = NormaId("daily-killer-5")

    @BeforeEach
    fun setUp() {
        ledger = InMemoryCurrencyLedger()
        eventBus = FakeDomainEventBus()
        val depositUc = DepositUseCase(ledger, eventBus)
        listener = DepositOnMissionCompletedListener(depositUc)
    }

    private fun event(currencyId: String?, amount: Long, xp: Long = 0L) =
        MissionCompleted(
            playerId = playerId,
            normaId = normaId,
            reward = NormaReward(currencyId = currencyId, currencyAmount = amount, xp = xp),
        )

    @Test
    fun `STAR reward 50 credits ledger and publishes one CurrencyChanged with MissionReward reason`() {
        listener.handle(event(currencyId = "STAR", amount = 50L))

        assertThat(ledger.balance(playerId, Currency.STAR)).isEqualTo(50L)
        val events = eventBus.publishedOfType<CurrencyChanged>()
        assertThat(events).hasSize(1)
        val ev = events.single()
        assertThat(ev.playerId).isEqualTo(playerId)
        assertThat(ev.currency).isEqualTo(Currency.STAR)
        assertThat(ev.delta).isEqualTo(50L)
        assertThat(ev.reason).isEqualTo(CurrencyChangeReason.MissionReward)
    }

    @Test
    fun `GOLD reward credits GOLD wallet, not STAR`() {
        listener.handle(event(currencyId = "GOLD", amount = 10L))

        assertThat(ledger.balance(playerId, Currency.GOLD)).isEqualTo(10L)
        assertThat(ledger.balance(playerId, Currency.STAR)).isEqualTo(0L)
    }

    @Test
    fun `lowercase currencyId is matched case-insensitively`() {
        listener.handle(event(currencyId = "gem", amount = 1L))

        assertThat(ledger.balance(playerId, Currency.GEM)).isEqualTo(1L)
    }

    @Test
    fun `null currencyId is a no-op`() {
        listener.handle(event(currencyId = null, amount = 100L))

        assertThat(ledger.balance(playerId, Currency.STAR)).isEqualTo(0L)
        assertThat(eventBus.published).isEmpty()
    }

    @Test
    fun `blank currencyId is a no-op`() {
        listener.handle(event(currencyId = "   ", amount = 100L))

        assertThat(eventBus.published).isEmpty()
    }

    @Test
    fun `unknown currencyId is a no-op (no exception)`() {
        listener.handle(event(currencyId = "DOGECOIN", amount = 100L))

        assertThat(eventBus.published).isEmpty()
        assertThat(ledger.balance(playerId, Currency.STAR)).isEqualTo(0L)
    }

    @Test
    fun `zero amount is a no-op (does not reach DepositUseCase InvalidAmount path)`() {
        listener.handle(event(currencyId = "STAR", amount = 0L))

        assertThat(eventBus.published).isEmpty()
        assertThat(ledger.balance(playerId, Currency.STAR)).isEqualTo(0L)
    }

    @Test
    fun `negative amount is a no-op`() {
        listener.handle(event(currencyId = "STAR", amount = -5L))

        assertThat(eventBus.published).isEmpty()
        assertThat(ledger.balance(playerId, Currency.STAR)).isEqualTo(0L)
    }
}
