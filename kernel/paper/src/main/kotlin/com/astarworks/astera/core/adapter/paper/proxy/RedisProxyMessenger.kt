package com.astarworks.astera.core.adapter.paper.proxy

import com.astarworks.astera.core.application.port.outbound.IProxyMessenger
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ProxyChannel
import com.astarworks.astera.core.domain.proxy.ProxyError
import com.astarworks.astera.core.domain.proxy.ProxyMessage
import com.astarworks.astera.core.domain.proxy.ServerName
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [IProxyMessenger] over Redis pub/sub (ADR-0179). Every backend listens on
 * `astera:proxy:to:<its name>` and `astera:proxy:all`; [sendMessage] publishes
 * a [ProxyFrameCodec] frame to the target's topic, [broadcast] to the shared
 * one (the sender receives its own broadcast too, like the in-memory loopback).
 *
 * [sendToServer] is "move this player": it is delegated to [playerConnector]
 * (the BungeeCord `Connect` plugin message on Paper).
 *
 * Inbound frames arrive on a Lettuce thread; [dispatch] hops them onto the
 * game thread before any handler runs.
 */
public class RedisProxyMessenger(
    private val localServer: ServerName,
    private val redisUri: RedisURI,
    private val playerConnector: (PlayerId, ServerName) -> Result<Unit, ProxyError>,
    private val dispatch: (() -> Unit) -> Unit,
) : IProxyMessenger {

    private val log = LoggerFactory.getLogger(javaClass)
    private val started = AtomicBoolean(false)
    private val subscribers: MutableMap<ProxyChannel, CopyOnWriteArrayList<Sub>> = ConcurrentHashMap()
    private var client: RedisClient? = null
    private var publishConnection: StatefulRedisConnection<String, ByteArray>? = null
    private var subscribeConnection: StatefulRedisPubSubConnection<String, ByteArray>? = null

    /** Connects and subscribes. Throws a Lettuce `RuntimeException` when Redis is unreachable. */
    public fun start() {
        if (!started.compareAndSet(false, true)) return
        val codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE)
        val newClient = RedisClient.create(redisUri)
        try {
            val pub = newClient.connect(codec)
            val sub = newClient.connectPubSub(codec)
            sub.addListener(object : RedisPubSubAdapter<String, ByteArray>() {
                override fun message(channel: String, message: ByteArray) = onFrame(message)
            })
            sub.sync().subscribe(topicTo(localServer), TOPIC_ALL)
            client = newClient
            publishConnection = pub
            subscribeConnection = sub
            log.info("RedisProxyMessenger {} connected to {}", localServer, redisUri)
        } catch (ex: RuntimeException) {
            started.set(false)
            runCatching { newClient.shutdown() }
            throw ex
        }
    }

    public fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { subscribeConnection?.close() }
        runCatching { publishConnection?.close() }
        runCatching { client?.shutdown() }
        subscribeConnection = null
        publishConnection = null
        client = null
    }

    override fun sendToServer(playerId: PlayerId, targetServer: ServerName): Result<Unit, ProxyError> =
        playerConnector(playerId, targetServer)

    override fun sendMessage(
        targetServer: ServerName,
        channel: ProxyChannel,
        payload: ByteArray,
    ): Result<Unit, ProxyError> = publish(topicTo(targetServer), channel, payload)

    override fun broadcast(channel: ProxyChannel, payload: ByteArray): Result<Unit, ProxyError> =
        publish(TOPIC_ALL, channel, payload)

    override fun subscribe(channel: ProxyChannel, handler: (ProxyMessage) -> Unit): IProxyMessenger.Subscription {
        val sub = Sub(channel, handler)
        subscribers.computeIfAbsent(channel) { CopyOnWriteArrayList() }.add(sub)
        return sub
    }

    private fun publish(topic: String, channel: ProxyChannel, payload: ByteArray): Result<Unit, ProxyError> {
        val conn = publishConnection ?: return Result.Failure(ProxyError.NoProxyConnection)
        val frame = ProxyFrameCodec.encode(ProxyMessage(localServer, channel, payload))
        conn.async().publish(topic, frame).whenComplete { _, error ->
            if (error != null) log.warn("Redis publish to {} failed", topic, error)
        }
        return Result.Success(Unit)
    }

    private fun onFrame(bytes: ByteArray) {
        val message = ProxyFrameCodec.decode(bytes) ?: run {
            log.warn("Dropping malformed proxy frame ({} bytes)", bytes.size)
            return
        }
        val targets = subscribers[message.channel]?.filter { it.isActive } ?: return
        if (targets.isEmpty()) return
        dispatch {
            targets.forEach { sub ->
                if (sub.isActive) {
                    runCatching { sub.handler(message) }
                        .onFailure { log.warn("Proxy handler for {} threw", message.channel, it) }
                }
            }
        }
    }

    private inner class Sub(
        val channel: ProxyChannel,
        val handler: (ProxyMessage) -> Unit,
    ) : IProxyMessenger.Subscription {
        private val active = AtomicBoolean(true)
        override val isActive: Boolean get() = active.get()
        override fun cancel() {
            if (active.compareAndSet(true, false)) subscribers[channel]?.remove(this)
        }
    }

    private companion object {
        const val TOPIC_ALL = "astera:proxy:all"
        fun topicTo(server: ServerName): String = "astera:proxy:to:${server.value}"
    }
}
