package com.astarworks.astera.stats.application.usecase.stats

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.service.InMemoryPlayerStatsRepository
import com.astarworks.astera.stats.domain.event.StatsUpdated
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class RecordKillUseCaseTest {

    private lateinit var repository: InMemoryPlayerStatsRepository
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var useCase: RecordKillUseCase
    private val playerId = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        repository = InMemoryPlayerStatsRepository()
        eventBus = FakeDomainEventBus()
        useCase = RecordKillUseCase(repository, eventBus)
    }

    @Test
    fun `execute increments kills from zero`() {
        val result = useCase.execute(playerId)
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()!!.kills).isEqualTo(1L)
    }

    @Test
    fun `execute increments kills on second call`() {
        useCase.execute(playerId)
        val result = useCase.execute(playerId)
        assertThat(result.getOrNull()!!.kills).isEqualTo(2L)
    }

    @Test
    fun `execute publishes StatsUpdated Kill event`() {
        useCase.execute(playerId)
        val events = eventBus.publishedOfType<StatsUpdated>()
        assertThat(events).hasSize(1)
        assertThat(events.first().playerId).isEqualTo(playerId)
        assertThat(events.first().kind).isEqualTo(StatsUpdated.Kind.Kill)
    }

    @Test
    fun `execute persists kills in repository`() {
        useCase.execute(playerId)
        assertThat(repository.find(playerId)!!.kills).isEqualTo(1L)
    }

    @Test
    fun `execute does not affect deaths`() {
        useCase.execute(playerId)
        assertThat(repository.find(playerId)!!.deaths).isEqualTo(0L)
    }
}
