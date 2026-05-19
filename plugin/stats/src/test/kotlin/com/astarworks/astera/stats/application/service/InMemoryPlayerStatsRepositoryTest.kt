package com.astarworks.astera.stats.application.service

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stats.PlayerStats
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryPlayerStatsRepositoryTest {

    private lateinit var repository: InMemoryPlayerStatsRepository
    private val playerId = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        repository = InMemoryPlayerStatsRepository()
    }

    @Test
    fun `find returns null for unknown player`() {
        assertThat(repository.find(playerId)).isNull()
    }

    @Test
    fun `upsert stores and find retrieves`() {
        val stats = PlayerStats.of(playerId, kills = 5L)
        repository.upsert(stats)
        assertThat(repository.find(playerId)!!.kills).isEqualTo(5L)
    }

    @Test
    fun `upsert overwrites existing entry`() {
        repository.upsert(PlayerStats.of(playerId, kills = 1L))
        repository.upsert(PlayerStats.of(playerId, kills = 10L))
        assertThat(repository.find(playerId)!!.kills).isEqualTo(10L)
    }

    @Test
    fun `all returns all stored entries`() {
        val id1 = PlayerId(UUID.randomUUID())
        val id2 = PlayerId(UUID.randomUUID())
        repository.upsert(PlayerStats.empty(id1))
        repository.upsert(PlayerStats.empty(id2))
        assertThat(repository.all()).hasSize(2)
    }

    @Test
    fun `upsert returns the stored stats`() {
        val stats = PlayerStats.of(playerId, deaths = 3L)
        val returned = repository.upsert(stats)
        assertThat(returned.deaths).isEqualTo(3L)
    }
}
