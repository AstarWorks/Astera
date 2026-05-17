package com.astarworks.astera.stats.application.usecase.stats

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.service.InMemoryPlayerStatsRepository
import com.astarworks.astera.stats.domain.event.StatsUpdated
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class RecordMatchPlayedUseCaseTest {

    private lateinit var repo: InMemoryPlayerStatsRepository
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var uc: RecordMatchPlayedUseCase
    private val playerId = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        repo = InMemoryPlayerStatsRepository()
        eventBus = FakeDomainEventBus()
        uc = RecordMatchPlayedUseCase(repo, eventBus)
    }

    @Test
    fun `execute increments matchesPlayed and publishes StatsUpdated MatchPlayed`() {
        val result = uc.execute(playerId)
        assertThat(result.isSuccess).isTrue()
        assertThat(repo.find(playerId)!!.matchesPlayed).isEqualTo(1L)
        val events = eventBus.publishedOfType<StatsUpdated>()
        assertThat(events).hasSize(1)
        assertThat(events.single().kind).isEqualTo(StatsUpdated.Kind.MatchPlayed)
    }

    @Test
    fun `repeated execute accumulates`() {
        repeat(4) { uc.execute(playerId) }
        assertThat(repo.find(playerId)!!.matchesPlayed).isEqualTo(4L)
    }
}
