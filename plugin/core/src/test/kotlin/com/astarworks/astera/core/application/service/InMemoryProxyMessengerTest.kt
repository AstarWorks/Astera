package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ProxyChannel
import com.astarworks.astera.core.domain.proxy.ProxyError
import com.astarworks.astera.core.domain.proxy.ProxyMessage
import com.astarworks.astera.core.domain.proxy.ServerName
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class InMemoryProxyMessengerTest {

    private val lobby = ServerName("lobby")
    private val game1 = ServerName("game-1")
    private val game2 = ServerName("game-2")
    private val transfer = ProxyChannel.TRANSFER

    private fun playerId(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    fun `sendToServer to an unknown target returns UnknownTargetServer`() {
        val m = InMemoryProxyMessenger(localServer = lobby)
        val outcome = m.sendToServer(playerId(), game1)
        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        val err = (outcome as Result.Failure).error
        assertThat(err).isInstanceOf(ProxyError.UnknownTargetServer::class.java)
        assertThat((err as ProxyError.UnknownTargetServer).server).isEqualTo(game1)
    }

    @Test
    fun `sendToServer to a known target records the transfer`() {
        val m = InMemoryProxyMessenger(localServer = lobby, neighbors = setOf(game1))
        val player = playerId()

        val outcome = m.sendToServer(player, game1)

        assertThat(outcome.isSuccess).isTrue()
        assertThat(m.transfersRecorded()).containsExactly(
            InMemoryProxyMessenger.TransferRequestRecord(player, game1),
        )
    }

    @Test
    fun `sendMessage to self loops back to local subscribers`() {
        val m = InMemoryProxyMessenger(localServer = lobby)
        val seen = CopyOnWriteArrayList<ProxyMessage>()
        m.subscribe(transfer) { msg -> seen.add(msg) }

        val outcome = m.sendMessage(lobby, transfer, byteArrayOf(1, 2, 3))

        assertThat(outcome.isSuccess).isTrue()
        assertThat(seen).hasSize(1)
        assertThat(seen[0].fromServer).isEqualTo(lobby)
        assertThat(seen[0].channel).isEqualTo(transfer)
        assertThat(seen[0].payload).containsExactly(1, 2, 3)
    }

    @Test
    fun `sendMessage to unknown server returns UnknownTargetServer`() {
        val m = InMemoryProxyMessenger(localServer = lobby)
        val outcome = m.sendMessage(game1, transfer, byteArrayOf())
        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `broadcast records once per known server and delivers loopback once`() {
        val m = InMemoryProxyMessenger(localServer = lobby, neighbors = setOf(game1, game2))
        val seen = CopyOnWriteArrayList<ProxyMessage>()
        m.subscribe(transfer) { msg -> seen.add(msg) }

        val outcome = m.broadcast(transfer, byteArrayOf(7))

        assertThat(outcome.isSuccess).isTrue()
        // 3 known servers (local + 2 neighbors) → 3 outbound records.
        assertThat(m.outboundRecorded()).hasSize(3)
        // But loopback delivers exactly once.
        assertThat(seen).hasSize(1)
    }

    @Test
    fun `cancelled subscription stops receiving messages`() {
        val m = InMemoryProxyMessenger(localServer = lobby)
        val seen = CopyOnWriteArrayList<ProxyMessage>()
        val sub = m.subscribe(transfer) { msg -> seen.add(msg) }

        m.publishInbound(ProxyMessage(lobby, transfer, byteArrayOf(1)))
        assertThat(seen).hasSize(1)

        sub.cancel()
        assertThat(sub.isActive).isFalse()

        m.publishInbound(ProxyMessage(lobby, transfer, byteArrayOf(2)))
        assertThat(seen).hasSize(1)
    }

    @Test
    fun `multiple subscribers on same channel each receive every message`() {
        val m = InMemoryProxyMessenger(localServer = lobby)
        val a = CopyOnWriteArrayList<ProxyMessage>()
        val b = CopyOnWriteArrayList<ProxyMessage>()
        m.subscribe(transfer) { msg -> a.add(msg) }
        m.subscribe(transfer) { msg -> b.add(msg) }

        m.publishInbound(ProxyMessage(lobby, transfer, byteArrayOf(42)))

        assertThat(a).hasSize(1)
        assertThat(b).hasSize(1)
    }

    @Test
    fun `addNeighbor extends the known set after construction`() {
        val m = InMemoryProxyMessenger(localServer = lobby)
        assertThat(m.sendToServer(playerId(), game1).isFailure).isTrue()
        m.addNeighbor(game1)
        assertThat(m.sendToServer(playerId(), game1).isSuccess).isTrue()
    }

    @Test
    fun `clearRecorded wipes inspection state`() {
        val m = InMemoryProxyMessenger(localServer = lobby, neighbors = setOf(game1))
        m.sendToServer(playerId(), game1)
        m.sendMessage(game1, transfer, byteArrayOf(1))
        assertThat(m.transfersRecorded()).hasSize(1)
        assertThat(m.outboundRecorded()).hasSize(1)

        m.clearRecorded()

        assertThat(m.transfersRecorded()).isEmpty()
        assertThat(m.outboundRecorded()).isEmpty()
    }

    @Test
    fun `ProxyMessage equality is by content`() {
        val a = ProxyMessage(lobby, transfer, byteArrayOf(1, 2, 3))
        val b = ProxyMessage(lobby, transfer, byteArrayOf(1, 2, 3))
        val c = ProxyMessage(lobby, transfer, byteArrayOf(1, 2, 4))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a).isNotEqualTo(c)
    }
}
