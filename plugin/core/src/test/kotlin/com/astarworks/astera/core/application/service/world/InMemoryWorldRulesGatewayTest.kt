package com.astarworks.astera.core.application.service.world

import com.astarworks.astera.core.domain.world.WorldDifficulty
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InMemoryWorldRulesGatewayTest {

    private val arena = WorldId("arena")

    @Test
    fun `defaults match vanilla when no setter has been called`() {
        val gw = InMemoryWorldRulesGateway()
        assertThat(gw.getDifficulty(arena)).isNull()
        assertThat(gw.isKeepInventoryOnDeath(arena)).isFalse()
        assertThat(gw.isDoMobSpawning(arena)).isTrue()
        assertThat(gw.isDoFireTick(arena)).isTrue()
        assertThat(gw.isDoWeatherCycle(arena)).isTrue()
        assertThat(gw.isDoDaylightCycle(arena)).isTrue()
        assertThat(gw.isPvP(arena)).isTrue()
    }

    @Test
    fun `setters are independent per axis`() {
        val gw = InMemoryWorldRulesGateway()
        gw.setDifficulty(arena, WorldDifficulty.HARD)
        gw.setKeepInventoryOnDeath(arena, true)
        gw.setDoFireTick(arena, false)

        assertThat(gw.getDifficulty(arena)).isEqualTo(WorldDifficulty.HARD)
        assertThat(gw.isKeepInventoryOnDeath(arena)).isTrue()
        assertThat(gw.isDoFireTick(arena)).isFalse()
        // Untouched axes stay at vanilla.
        assertThat(gw.isDoMobSpawning(arena)).isTrue()
        assertThat(gw.isPvP(arena)).isTrue()
    }

    @Test
    fun `worlds are tracked independently`() {
        val gw = InMemoryWorldRulesGateway()
        val lobby = WorldId("lobby")
        gw.setPvP(arena, false)
        gw.setPvP(lobby, true)

        assertThat(gw.isPvP(arena)).isFalse()
        assertThat(gw.isPvP(lobby)).isTrue()
    }
}
