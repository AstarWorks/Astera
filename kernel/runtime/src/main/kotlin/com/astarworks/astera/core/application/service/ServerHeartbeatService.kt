package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IProxyMessenger
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IServerLiveness
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.ProxyHeartbeat
import com.astarworks.astera.core.domain.proxy.ProxyChannel
import com.astarworks.astera.core.domain.proxy.ServerName
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Peer liveness over the proxy messenger (F3-6): broadcasts a heartbeat every
 * [beatEveryTicks] and treats a peer as down once none arrived for
 * [downAfterTicks] (5 s / 15 s by default). Time is scheduler ticks, so a
 * stalled main thread also stops its own heartbeats.
 *
 * Publishes [ProxyHeartbeat] with the up peers whenever that set changes.
 */
public class ServerHeartbeatService(
    private val localServer: ServerName,
    private val messenger: IProxyMessenger,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
    private val beatEveryTicks: Long = DEFAULT_BEAT_TICKS,
    private val downAfterTicks: Long = DEFAULT_DOWN_TICKS,
) : IServerLiveness {

    private val log = LoggerFactory.getLogger(javaClass)
    private val lastSeen: MutableMap<ServerName, Long> = ConcurrentHashMap()
    private var lastPublished: Set<ServerName>? = null
    private var subscription: IProxyMessenger.Subscription? = null
    private var task: IScheduler.TaskHandle? = null

    public fun start() {
        subscription = messenger.subscribe(ProxyChannel.HEARTBEAT) { message ->
            if (message.fromServer != localServer) {
                val first = lastSeen.put(message.fromServer, scheduler.currentTick()) == null
                if (first) {
                    log.info("Peer {} is up.", message.fromServer)
                    publishIfChanged()
                }
            }
        }
        task = scheduler.runRepeatingTicks(ExecutionScope.Global, initialDelayTicks = 0, periodTicks = beatEveryTicks) { beat() }
    }

    public fun stop() {
        subscription?.cancel()
        task?.cancel()
        subscription = null
        task = null
    }

    override fun isUp(server: ServerName): Boolean {
        val seen = lastSeen[server] ?: return false
        return scheduler.currentTick() - seen <= downAfterTicks
    }

    override fun upPeers(): Set<ServerName> = lastSeen.keys.filterTo(linkedSetOf()) { isUp(it) }

    private fun beat() {
        val sent = messenger.broadcast(ProxyChannel.HEARTBEAT, localServer.value.toByteArray(Charsets.UTF_8))
        if (sent is Result.Failure) log.warn("Heartbeat broadcast failed: {}", sent.error)
        for (peer in lastSeen.keys.filterNot { isUp(it) }) {
            if (lastSeen.remove(peer) != null) log.warn("Peer {} is down (no heartbeat for {} ticks).", peer, downAfterTicks)
        }
        publishIfChanged()
    }

    private fun publishIfChanged() {
        val up = upPeers()
        if (up == lastPublished) return
        lastPublished = up
        eventBus.publish(ProxyHeartbeat(localServer = localServer, knownPeers = up))
    }

    public companion object {
        public const val DEFAULT_BEAT_TICKS: Long = 100
        public const val DEFAULT_DOWN_TICKS: Long = 300
    }
}
