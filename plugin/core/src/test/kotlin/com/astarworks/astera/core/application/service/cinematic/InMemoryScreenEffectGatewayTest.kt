package com.astarworks.astera.core.application.service.cinematic

import com.astarworks.astera.core.application.port.outbound.cinematic.ScreenEffectError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.cinematic.Color
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryScreenEffectGatewayTest {

    private val playerId = PlayerId(UUID.randomUUID())

    @Test
    fun `fade returns handle and records active`() {
        val g = InMemoryScreenEffectGateway()
        val r = g.fade(playerId, 0.0, 1.0, 20, Color.BLACK)
        assertThat(r.isSuccess).isTrue()
        val handle = (r as Result.Success).value
        assertThat(g.active(playerId)).contains(handle)
    }

    @Test
    fun `fade rejects out-of-range alpha`() {
        val g = InMemoryScreenEffectGateway()
        val r = g.fade(playerId, fromAlpha = 1.5, toAlpha = 1.0, durationTicks = 10, color = Color.BLACK)
        assertThat(r.isFailure).isTrue()
        assertThat((r as Result.Failure).error).isInstanceOf(ScreenEffectError.InvalidAlpha::class.java)
    }

    @Test
    fun `freeze rejects negative duration`() {
        val g = InMemoryScreenEffectGateway()
        val r = g.freeze(playerId, durationTicks = -1)
        assertThat(r.isFailure).isTrue()
    }

    @Test
    fun `cancel removes handle from active`() {
        val g = InMemoryScreenEffectGateway()
        val handle = (g.fade(playerId, 0.0, 1.0, 20, Color.BLACK) as Result.Success).value
        g.cancel(handle)
        assertThat(g.active(playerId)).doesNotContain(handle)
    }

    @Test
    fun `cancel on unknown handle is idempotent`() {
        val g = InMemoryScreenEffectGateway()
        val handle = (g.fade(playerId, 0.0, 1.0, 20, Color.BLACK) as Result.Success).value
        g.cancel(handle)
        val r = g.cancel(handle) // second time
        assertThat(r.isSuccess).isTrue()
    }
}
