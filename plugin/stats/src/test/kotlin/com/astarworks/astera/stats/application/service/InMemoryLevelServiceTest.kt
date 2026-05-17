package com.astarworks.astera.stats.application.service

import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.domain.level.Level
import com.astarworks.astera.stats.domain.level.Xp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryLevelServiceTest {

    private lateinit var service: InMemoryLevelService
    private val playerId = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        service = InMemoryLevelService()
    }

    @Test
    fun `new player starts at level 1 with 0 xp`() {
        assertThat(service.currentLevel(playerId)).isEqualTo(Level.ONE)
        assertThat(service.totalXp(playerId)).isEqualTo(Xp.ZERO)
    }

    @Test
    fun `grantXp accumulates correctly`() {
        service.grantXp(playerId, Xp(50L), XpSource.Kill)
        service.grantXp(playerId, Xp(30L), XpSource.Kill)
        assertThat(service.totalXp(playerId)).isEqualTo(Xp(80L))
    }

    @Test
    fun `grantXp does not level up below threshold`() {
        val result = service.grantXp(playerId, Xp(99L), XpSource.Kill).getOrNull()!!
        assertThat(result.leveledUp).isFalse()
        assertThat(result.newLevel).isEqualTo(Level.ONE)
    }

    @Test
    fun `grantXp detects level up at threshold`() {
        val result = service.grantXp(playerId, Xp(100L), XpSource.Kill).getOrNull()!!
        assertThat(result.leveledUp).isTrue()
        assertThat(result.oldLevel).isEqualTo(Level.ONE)
        assertThat(result.newLevel).isEqualTo(Level(2))
        assertThat(result.totalXp).isEqualTo(Xp(100L))
    }

    @Test
    fun `grantXp accumulates across multiple calls and detects level up`() {
        service.grantXp(playerId, Xp(50L), XpSource.Kill)
        val result = service.grantXp(playerId, Xp(50L), XpSource.Kill).getOrNull()!!
        assertThat(result.leveledUp).isTrue()
        assertThat(result.newLevel).isEqualTo(Level(2))
    }

    @Test
    fun `grantXp returns LevelChange with correct totalXp`() {
        service.grantXp(playerId, Xp(100L), XpSource.Kill)
        val result = service.grantXp(playerId, Xp(200L), XpSource.Kill).getOrNull()!!
        // after 300 total xp → level 3
        assertThat(result.leveledUp).isTrue()
        assertThat(result.newLevel).isEqualTo(Level(3))
        assertThat(result.totalXp).isEqualTo(Xp(300L))
    }

    @Test
    fun `currentLevel updates after grantXp`() {
        service.grantXp(playerId, Xp(100L), XpSource.Kill)
        assertThat(service.currentLevel(playerId)).isEqualTo(Level(2))
    }
}
