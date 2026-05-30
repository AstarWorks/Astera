package com.astarworks.astera.economy.application.eventbus.listeners.onboarding

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.event.TutorialCompleted
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import com.astarworks.astera.economy.application.service.InMemoryCurrencyLedger
import com.astarworks.astera.economy.application.usecase.currency.DepositUseCase
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave K 5integration-wire (ADR-0128) — scenario D (reward half).
 *
 * Proves: `TutorialCompleted` → `DepositUseCase` credits the configured
 * STAR amount with the [CurrencyChangeReason.TutorialReward] reason.
 */
class GrantRewardOnTutorialCompletedListenerTest {

    private val playerId = PlayerId(UUID.randomUUID())
    private val tutorialId = TutorialScriptId("astera-first-steps")

    @Test
    fun `tutorial completion credits 50 STAR with TutorialReward reason`() {
        val bus = FakeDomainEventBus()
        val ledger = InMemoryCurrencyLedger()
        val deposit = DepositUseCase(ledger, bus)
        val listener = GrantRewardOnTutorialCompletedListener(deposit)

        listener.handle(TutorialCompleted(playerId = playerId, tutorialId = tutorialId))

        assertThat(ledger.balance(playerId, Currency.STAR)).isEqualTo(50L)

        val events = bus.publishedOfType<CurrencyChanged>()
        assertThat(events).hasSize(1)
        assertThat(events[0].playerId).isEqualTo(playerId)
        assertThat(events[0].currency).isEqualTo(Currency.STAR)
        assertThat(events[0].delta).isEqualTo(50L)
        assertThat(events[0].reason).isEqualTo(CurrencyChangeReason.TutorialReward)
    }

    @Test
    fun `custom amount + currency overrides defaults`() {
        val bus = FakeDomainEventBus()
        val ledger = InMemoryCurrencyLedger()
        val deposit = DepositUseCase(ledger, bus)
        val listener = GrantRewardOnTutorialCompletedListener(
            deposit = deposit,
            currency = Currency.GOLD,
            amount = 200L,
        )

        listener.handle(TutorialCompleted(playerId = playerId, tutorialId = tutorialId))

        assertThat(ledger.balance(playerId, Currency.GOLD)).isEqualTo(200L)
    }
}
