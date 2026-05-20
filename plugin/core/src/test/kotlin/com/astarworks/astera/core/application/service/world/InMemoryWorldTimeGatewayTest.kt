package com.astarworks.astera.core.application.service.world

import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InMemoryWorldTimeGatewayTest {

    private val arena = WorldId("arena")

    @Test
    fun `getCurrentTime returns null for unknown world`() {
        val gw = InMemoryWorldTimeGateway()
        assertThat(gw.getCurrentTime(arena)).isNull()
    }

    @Test
    fun `setTime then getCurrentTime returns the value normalised to 0_24000`() {
        val gw = InMemoryWorldTimeGateway()
        gw.setTime(arena, 6000)
        assertThat(gw.getCurrentTime(arena)).isEqualTo(6000L)

        gw.setTime(arena, 30000) // wraps to 6000
        assertThat(gw.getCurrentTime(arena)).isEqualTo(6000L)

        gw.setTime(arena, -1) // wraps to 23999
        assertThat(gw.getCurrentTime(arena)).isEqualTo(23999L)
    }

    @Test
    fun `freezeTime toggles state and isTimeFrozen reflects it`() {
        val gw = InMemoryWorldTimeGateway()
        assertThat(gw.isTimeFrozen(arena)).isFalse()

        gw.freezeTime(arena, true)
        assertThat(gw.isTimeFrozen(arena)).isTrue()

        gw.freezeTime(arena, false)
        assertThat(gw.isTimeFrozen(arena)).isFalse()
    }

    @Test
    fun `freezeTime initialises currentTime to 0 if never set`() {
        val gw = InMemoryWorldTimeGateway()
        gw.freezeTime(arena, true)
        assertThat(gw.getCurrentTime(arena)).isEqualTo(0L)
    }

    @Test
    fun `unknown world stays unknown until setTime or freezeTime`() {
        val gw = InMemoryWorldTimeGateway()
        val other = WorldId("other")
        gw.setTime(arena, 100)

        assertThat(gw.getCurrentTime(other)).isNull()
        assertThat(gw.isTimeFrozen(other)).isFalse()
    }
}
