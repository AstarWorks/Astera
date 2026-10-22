package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IProxyMessenger
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.ProxyHeartbeat
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ProxyChannel
import com.astarworks.astera.core.domain.proxy.ProxyError
import com.astarworks.astera.core.domain.proxy.ProxyMessage
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ServerHeartbeatServiceTest {

    private val lobby = ServerName("lobby")
    private val game = ServerName("game")

    private class Wire : IProxyMessenger {
        var broadcasts = 0
        private val handlers = mutableListOf<(ProxyMessage) -> Unit>()
        override fun sendToServer(playerId: PlayerId, targetServer: ServerName): Result<Unit, ProxyError> = Result.Success(Unit)
        override fun sendMessage(targetServer: ServerName, channel: ProxyChannel, payload: ByteArray): Result<Unit, ProxyError> =
            Result.Success(Unit)
        override fun broadcast(channel: ProxyChannel, payload: ByteArray): Result<Unit, ProxyError> {
            broadcasts++
            return Result.Success(Unit)
        }
        override fun subscribe(channel: ProxyChannel, handler: (ProxyMessage) -> Unit): IProxyMessenger.Subscription {
            handlers += handler
            return object : IProxyMessenger.Subscription {
                override fun cancel() { handlers -= handler }
                override val isActive: Boolean get() = handler in handlers
            }
        }
        fun beatFrom(server: ServerName) =
            handlers.toList().forEach { it(ProxyMessage(server, ProxyChannel.HEARTBEAT, server.value.toByteArray())) }
    }

    @Test
    fun `beats every 5 seconds and marks a peer down after 15 seconds of silence`() {
        val scheduler = FakeScheduler()
        val wire = Wire()
        val bus = InMemoryDomainEventBus()
        val published = mutableListOf<ProxyHeartbeat>()
        bus.subscribe(ProxyHeartbeat::class) { published += it }
        val heartbeat = ServerHeartbeatService(lobby, wire, scheduler, bus).also { it.start() }

        scheduler.advanceTicks(1)
        assertThat(wire.broadcasts).isGreaterThanOrEqualTo(1)
        assertThat(heartbeat.isUp(game)).isFalse()

        wire.beatFrom(game)
        assertThat(heartbeat.isUp(game)).isTrue()
        assertThat(published.last().knownPeers).containsExactly(game)

        scheduler.advanceTicks(280) // 14 s: still alive
        assertThat(heartbeat.isUp(game)).isTrue()
        wire.beatFrom(game)
        scheduler.advanceTicks(290)
        assertThat(heartbeat.isUp(game)).isTrue()

        scheduler.advanceTicks(30) // > 15 s since the last beat
        assertThat(heartbeat.isUp(game)).isFalse()
        assertThat(published.last().knownPeers).isEmpty()
        assertThat(wire.broadcasts).isGreaterThan(5)

        wire.beatFrom(game) // back again
        assertThat(heartbeat.isUp(game)).isTrue()
    }

    @Test
    fun `own beats are ignored`() {
        val wire = Wire()
        val heartbeat = ServerHeartbeatService(lobby, wire, FakeScheduler(), InMemoryDomainEventBus()).also { it.start() }
        wire.beatFrom(lobby)
        assertThat(heartbeat.upPeers()).isEmpty()
    }
}
