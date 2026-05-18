package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.combat.domain.event.AssistCredited
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.service.InMemoryPlayerStatsRepository
import com.astarworks.astera.stats.application.usecase.stats.RecordAssistUseCase
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class RecordAssistOnAssistCreditedListenerTest {

    private val assister = PlayerId(UUID.randomUUID())
    private val victim = PlayerId(UUID.randomUUID())

    private fun event() = AssistCredited(
        player = assister,
        victim = victim,
        matchId = null,
        atTick = 50L,
    )

    @Test
    fun `handle increments assists from empty`() {
        val repo = InMemoryPlayerStatsRepository()
        val listener = RecordAssistOnAssistCreditedListener(
            RecordAssistUseCase(repo, FakeDomainEventBus()),
        )
        listener.handle(event())
        assertThat(repo.find(assister)!!.assists).isEqualTo(1L)
    }

    @Test
    fun `handle increments assists across multiple events`() {
        val repo = InMemoryPlayerStatsRepository()
        val listener = RecordAssistOnAssistCreditedListener(
            RecordAssistUseCase(repo, FakeDomainEventBus()),
        )
        listener.handle(event())
        listener.handle(event())
        listener.handle(event())
        assertThat(repo.find(assister)!!.assists).isEqualTo(3L)
    }
}
