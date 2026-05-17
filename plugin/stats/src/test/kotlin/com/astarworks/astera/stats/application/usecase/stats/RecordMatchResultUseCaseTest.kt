package com.astarworks.astera.stats.application.usecase.stats

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.service.InMemoryPlayerStatsRepository
import com.astarworks.astera.stats.domain.event.StatsUpdated
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class RecordMatchResultUseCaseTest {

    private lateinit var repo: InMemoryPlayerStatsRepository
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var uc: RecordMatchResultUseCase
    private val playerId = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        repo = InMemoryPlayerStatsRepository()
        eventBus = FakeDomainEventBus()
        uc = RecordMatchResultUseCase(repo, eventBus)
    }

    @Test
    fun `WIN increments wins and publishes StatsUpdated Win`() {
        val result = uc.execute(playerId, RecordMatchResultUseCase.Outcome.WIN)
        assertThat(result.isSuccess).isTrue()
        assertThat(repo.find(playerId)!!.wins).isEqualTo(1L)
        assertThat(repo.find(playerId)!!.losses).isEqualTo(0L)
        val events = eventBus.publishedOfType<StatsUpdated>()
        assertThat(events).hasSize(1)
        assertThat(events.single().kind).isEqualTo(StatsUpdated.Kind.Win)
    }

    @Test
    fun `LOSS increments losses and publishes StatsUpdated Loss`() {
        uc.execute(playerId, RecordMatchResultUseCase.Outcome.LOSS)
        assertThat(repo.find(playerId)!!.losses).isEqualTo(1L)
        assertThat(repo.find(playerId)!!.wins).isEqualTo(0L)
        val events = eventBus.publishedOfType<StatsUpdated>()
        assertThat(events.single().kind).isEqualTo(StatsUpdated.Kind.Loss)
    }

    @Test
    fun `repeated WIN accumulates`() {
        repeat(3) { uc.execute(playerId, RecordMatchResultUseCase.Outcome.WIN) }
        assertThat(repo.find(playerId)!!.wins).isEqualTo(3L)
    }
}
