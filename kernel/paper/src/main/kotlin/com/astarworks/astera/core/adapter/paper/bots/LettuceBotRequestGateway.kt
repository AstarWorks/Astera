package com.astarworks.astera.core.adapter.paper.bots

import com.astarworks.astera.core.application.bots.BotMatchWire
import com.astarworks.astera.core.application.bots.BotRequest
import com.astarworks.astera.core.application.bots.BotStatus
import com.astarworks.astera.core.application.port.outbound.bots.BotRequestError
import com.astarworks.astera.core.application.port.outbound.bots.IBotRequestGateway
import com.astarworks.astera.core.domain.Result
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [IBotRequestGateway] over Redis pub/sub (ADR-0248): publishes [BotRequest] JSON on `astera:bots:request` and
 * listens on `astera:bots:status`. Inbound messages arrive on a Lettuce thread and are handed to [dispatch]
 * (the game thread) before any handler runs.
 */
public class LettuceBotRequestGateway(
    private val redisUri: RedisURI,
    private val dispatch: (() -> Unit) -> Unit,
) : IBotRequestGateway {

    private val log = LoggerFactory.getLogger(javaClass)
    private val started = AtomicBoolean(false)
    private val handlers = CopyOnWriteArrayList<(BotStatus) -> Unit>()
    private var client: RedisClient? = null
    private var publishConnection: StatefulRedisConnection<String, String>? = null
    private var subscribeConnection: StatefulRedisPubSubConnection<String, String>? = null

    /** Connects and subscribes. Throws a Lettuce `RuntimeException` when Redis is unreachable. */
    public fun start() {
        if (!started.compareAndSet(false, true)) return
        val newClient = RedisClient.create(redisUri)
        try {
            val pub = newClient.connect()
            val sub = newClient.connectPubSub()
            sub.addListener(object : RedisPubSubAdapter<String, String>() {
                override fun message(channel: String, message: String) = onStatus(message)
            })
            sub.sync().subscribe(BotMatchWire.STATUS_CHANNEL)
            client = newClient
            publishConnection = pub
            subscribeConnection = sub
            log.info(
                "Bot request gateway connected to {} (request={}, status={})",
                redisUri, BotMatchWire.REQUEST_CHANNEL, BotMatchWire.STATUS_CHANNEL,
            )
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

    override fun publish(request: BotRequest): Result<Unit, BotRequestError> {
        val conn = publishConnection ?: return Result.Failure(BotRequestError.Unavailable)
        val payload = BotMatchWire.encode(request)
        conn.async().publish(BotMatchWire.REQUEST_CHANNEL, payload).whenComplete { receivers, error ->
            if (error != null) {
                log.warn("Bot request {} publish failed", request.requestId, error)
            } else {
                log.info(
                    "Bot request {} published on {} ({} runner(s) listening): {}",
                    request.requestId, BotMatchWire.REQUEST_CHANNEL, receivers, payload,
                )
            }
        }
        return Result.Success(Unit)
    }

    override fun subscribe(handler: (BotStatus) -> Unit): IBotRequestGateway.Subscription {
        handlers += handler
        return object : IBotRequestGateway.Subscription {
            override fun cancel() {
                handlers -= handler
            }
        }
    }

    private fun onStatus(payload: String) {
        val status = BotMatchWire.decodeStatus(payload) ?: run {
            log.warn("Dropping malformed bot status: {}", payload.take(200))
            return
        }
        dispatch {
            log.info("Bot status for {}: {} bots={} reason={}", status.requestId, status.state, status.bots, status.reason)
            handlers.forEach { h -> runCatching { h(status) }.onFailure { log.warn("Bot status handler threw", it) } }
        }
    }
}
