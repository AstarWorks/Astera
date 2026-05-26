package com.astarworks.astera.core.application.service.cinematic

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryFreelookGatewayTest {

    private val playerId = PlayerId(UUID.randomUUID())

    @Test
    fun `enableFreelook records session`() {
        val g = InMemoryFreelookGateway()
        val r = g.enableFreelook(playerId, Vec3(1.0, 2.0, 3.0))
        assertThat(r.isSuccess).isTrue()
        val session = (r as Result.Success).value
        assertThat(session.playerId).isEqualTo(playerId)
        assertThat(session.anchorPosition).isEqualTo(Vec3(1.0, 2.0, 3.0))
        assertThat(g.session(playerId)).isEqualTo(session)
    }

    @Test
    fun `enableFreelook twice replaces session`() {
        val g = InMemoryFreelookGateway()
        g.enableFreelook(playerId, Vec3.ZERO)
        g.enableFreelook(playerId, Vec3(5.0, 5.0, 5.0))
        assertThat(g.session(playerId)?.anchorPosition).isEqualTo(Vec3(5.0, 5.0, 5.0))
    }

    @Test
    fun `disableFreelook removes session and is idempotent`() {
        val g = InMemoryFreelookGateway()
        g.enableFreelook(playerId, Vec3.ZERO)
        g.disableFreelook(playerId)
        assertThat(g.session(playerId)).isNull()
        // second time
        val r = g.disableFreelook(playerId)
        assertThat(r.isSuccess).isTrue()
    }

    @Test
    fun `all returns active sessions`() {
        val g = InMemoryFreelookGateway()
        val p2 = PlayerId(UUID.randomUUID())
        g.enableFreelook(playerId, Vec3.ZERO)
        g.enableFreelook(p2, Vec3(1.0, 0.0, 0.0))
        assertThat(g.all()).hasSize(2)
    }
}
