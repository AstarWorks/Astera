package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IProxyMessenger
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ProxyChannel
import com.astarworks.astera.core.domain.proxy.ProxyError
import com.astarworks.astera.core.domain.proxy.ProxyMessage
import com.astarworks.astera.core.domain.proxy.ServerName
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The simulated Velocity proxy + Redis between several [GameSim] servers
 * (ADR-0179). Each server gets a [node] — its [IProxyMessenger] — and is
 * [register]ed once booted so the network can move players between worlds.
 *
 * Messages are queued and delivered by [pump] (the real path is asynchronous
 * too: Redis hop, then the receiving server's main thread). Player moves are
 * immediate: the entity leaves one sim (quit event) and appears in the other
 * (join event), keeping its id and name.
 */
public class SimProxyNetwork {

    private val sims: MutableMap<ServerName, GameSim> = linkedMapOf()
    private val nodes: MutableMap<ServerName, Node> = linkedMapOf()
    private val queue: MutableList<Pair<ServerName, ProxyMessage>> = mutableListOf()

    /** Every message sent so far, for assertions. */
    public val sent: MutableList<ProxyMessage> = mutableListOf()

    /** Every player move so far as `(player, from, to)`. */
    public val moves: MutableList<Triple<PlayerId, ServerName, ServerName>> = mutableListOf()

    private val down: MutableSet<ServerName> = linkedSetOf()

    /** Takes [name] off the network (crashed: no ticks, no messages in or out) or brings it back. */
    public fun setDown(name: ServerName, isDown: Boolean) {
        if (isDown) down += name else down -= name
    }

    public fun node(name: ServerName): IProxyMessenger = nodes.getOrPut(name) { Node(name) }

    public fun register(name: ServerName, sim: GameSim) {
        sims[name] = sim
    }

    /** Delivers every queued message (and any a handler queues in turn). */
    public fun pump() {
        while (queue.isNotEmpty()) {
            val (target, message) = queue.removeAt(0)
            if (target !in down) nodes[target]?.deliver(message)
        }
    }

    /** Steps every server one tick at a time, pumping the network between ticks. */
    public fun step(ticks: Int) {
        repeat(ticks) {
            sims.filterKeys { it !in down }.values.forEach { it.step(1) }
            pump()
        }
    }

    private fun move(playerId: PlayerId, from: ServerName, to: ServerName): Result<Unit, ProxyError> {
        val source = sims[from] ?: return Result.Failure(ProxyError.NoProxyConnection)
        val target = sims[to]?.takeIf { to !in down } ?: return Result.Failure(ProxyError.UnknownTargetServer(to))
        val entity = source.detachPlayer(playerId) ?: return Result.Failure(ProxyError.NoProxyConnection)
        moves += Triple(playerId, from, to)
        target.attachPlayer(playerId, entity.name)
        return Result.Success(Unit)
    }

    private inner class Node(private val local: ServerName) : IProxyMessenger {
        private val subscriptions = CopyOnWriteArrayList<Sub>()

        override fun sendToServer(playerId: PlayerId, targetServer: ServerName): Result<Unit, ProxyError> =
            move(playerId, local, targetServer)

        override fun sendMessage(targetServer: ServerName, channel: ProxyChannel, payload: ByteArray): Result<Unit, ProxyError> {
            if (targetServer !in nodes) return Result.Failure(ProxyError.UnknownTargetServer(targetServer))
            if (local in down) return Result.Success(Unit)
            val message = ProxyMessage(local, channel, payload.copyOf())
            sent += message
            queue += targetServer to message
            return Result.Success(Unit)
        }

        override fun broadcast(channel: ProxyChannel, payload: ByteArray): Result<Unit, ProxyError> {
            nodes.keys.forEach { sendMessage(it, channel, payload) }
            return Result.Success(Unit)
        }

        override fun subscribe(channel: ProxyChannel, handler: (ProxyMessage) -> Unit): IProxyMessenger.Subscription =
            Sub(channel, handler).also { subscriptions += it }

        fun deliver(message: ProxyMessage) {
            subscriptions.filter { it.channel == message.channel && it.isActive }.forEach { it.handler(message) }
        }

        private inner class Sub(val channel: ProxyChannel, val handler: (ProxyMessage) -> Unit) : IProxyMessenger.Subscription {
            override var isActive: Boolean = true
                private set

            override fun cancel() {
                isActive = false
                subscriptions.remove(this)
            }
        }
    }
}
