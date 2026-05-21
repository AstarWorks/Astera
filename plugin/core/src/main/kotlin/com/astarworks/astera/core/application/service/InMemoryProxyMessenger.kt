package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IProxyMessenger
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ProxyChannel
import com.astarworks.astera.core.domain.proxy.ProxyError
import com.astarworks.astera.core.domain.proxy.ProxyMessage
import com.astarworks.astera.core.domain.proxy.ServerName
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-memory [IProxyMessenger] for domain tests and single-server dev.
 *
 * Three concrete behaviours that justify keeping this as the priority-0
 * default rather than a Phase 6 carve-out:
 *
 * 1. Self-loopback. Messages sent to the configured [localServer] are
 *    immediately delivered to local subscribers — useful for use-cases that
 *    treat "local" as a degenerate proxy hop without branching.
 * 2. Test-server topology. Constructor [neighbors] sets the (also-in-memory)
 *    set of known peer servers. [sendMessage] / [sendToServer] for unknown
 *    targets returns [ProxyError.UnknownTargetServer] instead of silently
 *    succeeding.
 * 3. No paper. Domain tests use this without any Bukkit harness; the
 *    [sendToServer] call simply records that a transfer was requested and
 *    [transfersRecorded] exposes them for assertion.
 *
 * Thread-safety: subscribers and recorded transfers use concurrent
 * collections so [subscribe] / [publishInbound] / [sendToServer] are safe
 * to call from any thread (use-cases may execute on Paper main thread,
 * tests on a JUnit worker).
 */
public class InMemoryProxyMessenger(
    public val localServer: ServerName = ServerName("local"),
    neighbors: Set<ServerName> = emptySet(),
) : IProxyMessenger {

    private val knownServers: MutableSet<ServerName> = ConcurrentHashMap.newKeySet<ServerName>().apply {
        add(localServer)
        addAll(neighbors)
    }

    private val subscribers: MutableMap<ProxyChannel, CopyOnWriteArrayList<InMemorySubscription>> =
        ConcurrentHashMap()

    private val recordedTransfers: MutableList<TransferRequestRecord> = CopyOnWriteArrayList()
    private val recordedOutbound: MutableList<OutboundRecord> = CopyOnWriteArrayList()

    /** Test inspection: every [sendToServer] call recorded in order. */
    public fun transfersRecorded(): List<TransferRequestRecord> = recordedTransfers.toList()

    /** Test inspection: every [sendMessage] / [broadcast] call recorded in order. */
    public fun outboundRecorded(): List<OutboundRecord> = recordedOutbound.toList()

    /** Test inspection: clear all recorded state. */
    public fun clearRecorded() {
        recordedTransfers.clear()
        recordedOutbound.clear()
    }

    /** Test hook: deliver [message] to every subscriber on its channel. */
    public fun publishInbound(message: ProxyMessage) {
        subscribers[message.channel]?.forEach { sub -> if (sub.isActive) sub.handler(message) }
    }

    /** Test hook: extend known-peer set after construction. */
    public fun addNeighbor(server: ServerName) {
        knownServers.add(server)
    }

    override fun sendToServer(playerId: PlayerId, targetServer: ServerName): Result<Unit, ProxyError> {
        if (targetServer !in knownServers) {
            return Result.failure(ProxyError.UnknownTargetServer(targetServer))
        }
        recordedTransfers.add(TransferRequestRecord(playerId, targetServer))
        return Result.success(Unit)
    }

    override fun sendMessage(
        targetServer: ServerName,
        channel: ProxyChannel,
        payload: ByteArray,
    ): Result<Unit, ProxyError> {
        if (targetServer !in knownServers) {
            return Result.failure(ProxyError.UnknownTargetServer(targetServer))
        }
        recordedOutbound.add(OutboundRecord(targetServer, channel, payload.copyOf()))
        // Loopback: deliver to local subscribers immediately when target is self.
        if (targetServer == localServer) {
            publishInbound(ProxyMessage(fromServer = localServer, channel = channel, payload = payload))
        }
        return Result.success(Unit)
    }

    override fun broadcast(channel: ProxyChannel, payload: ByteArray): Result<Unit, ProxyError> {
        knownServers.forEach { target ->
            recordedOutbound.add(OutboundRecord(target, channel, payload.copyOf()))
        }
        // Loopback delivery once, not once-per-broadcast-target.
        publishInbound(ProxyMessage(fromServer = localServer, channel = channel, payload = payload))
        return Result.success(Unit)
    }

    override fun subscribe(
        channel: ProxyChannel,
        handler: (ProxyMessage) -> Unit,
    ): IProxyMessenger.Subscription {
        val sub = InMemorySubscription(channel, handler) { ch, s -> removeSubscriber(ch, s) }
        subscribers.computeIfAbsent(channel) { CopyOnWriteArrayList() }.add(sub)
        return sub
    }

    private fun removeSubscriber(channel: ProxyChannel, sub: InMemorySubscription) {
        subscribers[channel]?.remove(sub)
    }

    public data class TransferRequestRecord(val playerId: PlayerId, val targetServer: ServerName)

    public class OutboundRecord(
        public val targetServer: ServerName,
        public val channel: ProxyChannel,
        public val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is OutboundRecord) return false
            return targetServer == other.targetServer &&
                channel == other.channel &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = targetServer.hashCode()
            result = 31 * result + channel.hashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    private class InMemorySubscription(
        val channel: ProxyChannel,
        val handler: (ProxyMessage) -> Unit,
        private val onCancel: (ProxyChannel, InMemorySubscription) -> Unit,
    ) : IProxyMessenger.Subscription {
        private val active = AtomicBoolean(true)
        override val isActive: Boolean get() = active.get()
        override fun cancel() {
            if (active.compareAndSet(true, false)) {
                onCancel(channel, this)
            }
        }
    }
}
