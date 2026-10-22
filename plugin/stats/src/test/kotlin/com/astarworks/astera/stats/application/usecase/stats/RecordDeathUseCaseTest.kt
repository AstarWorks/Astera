package com.astarworks.astera.stats.application.usecase.stats

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.service.InMemoryPlayerStatsRepository
import com.astarworks.astera.stats.domain.event.StatsUpdated
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class RecordDeathUseCaseTest {
    @Test
    fun `each death increments the victim and publishes the updated stat`() {
        val repo = InMemoryPlayerStatsRepository()
        val bus = FakeDomainEventBus()
        val victim = PlayerId(UUID.randomUUID())
        val record = RecordDeathUseCase(repo, bus)
        repeat(2) { record.execute(victim) }
        assertThat(repo.find(victim)!!.deaths).isEqualTo(2L)
        assertThat(bus.publishedOfType<StatsUpdated>()).hasSize(2)
            .allMatch { it.playerId == victim && it.kind == StatsUpdated.Kind.Death }
    }
}
