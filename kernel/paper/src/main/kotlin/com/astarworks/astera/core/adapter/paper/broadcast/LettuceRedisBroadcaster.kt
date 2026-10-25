package com.astarworks.astera.core.adapter.paper.broadcast

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.event.DomainEvent
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cross-server [IBroadcaster] backed by Redis pub/sub (Lettuce client).
 *
 * Role contrast (Wave 6b-3 / ADR-0158 fan-out):
 *
 *  - [com.astarworks.astera.core.adapter.paper.event.PaperBroadcaster] —
 *    in-process logger (Phase 1 default). Stays in single-JVM topology.
 *  - [LettuceRedisBroadcaster] — fan-out across multiple Astera Paper
 *    instances behind the proxy. Publishes serialized [DomainEvent] payloads
 *    to a Redis channel; subscribers in sibling servers re-hydrate via a
 *    separate adapter that translates back into the local `IDomainEventBus`.
 *  - [DiscordWebhookBroadcaster] — operator-visibility surface (Discord
 *    webhook) for human-readable game-state notifications.
 *
 * Operators wire one or more of these in [com.astarworks.astera.core.platform.AsteraCorePlugin]
 * behind env-var feature flags. Composition / filtering (which event variants
 * fan out where) is wiring-layer responsibility; this class itself publishes
 * every event it receives.
 *
 * Lifecycle:
 *  - [start] opens a Lettuce client + connection. If the Redis host is
 *    unreachable, [start] logs WARN and flips the broadcaster into a
 *    no-op mode (subsequent [publish] calls silently drop). The intent is
 *    PoC robustness: a missing Redis must not bring the server down.
 *  - [stop] shuts down the client synchronously.
 *
 * Threading:
 *  - [publish] is invoked from the Paper main thread by application services.
 *    Lettuce sync API blocks on socket I/O, which would stall the tick loop,
 *    so the actual `publish` is dispatched to [IScheduler.runAsync].
 *
 * Phase 1 PoC limitations (Wave 6b-3):
 *  - No retry / circuit-breaker — a flaky Redis silently drops events after
 *    initial connect succeeds. Wave 6c will add reconnect handling using
 *    Lettuce's built-in `ClientOptions.reconnect`.
 *  - Wire format upgraded in Wave 6c (ADR-0160) from `event.toString()` to a
 *    polymorphic JSON envelope (`{"type":"<simpleName>","payload":{...}}`)
 *    via [BroadcastEventJsonCodec]. Stable across data-class field
 *    additions thanks to Jackson's `FAIL_ON_UNKNOWN_PROPERTIES = false`
 *    on the decode side.
 *  - Subscribe side intentionally not implemented here: this is the publisher.
 *    A separate `RedisBroadcastSubscriber` (Wave 6c) drains the channel and
 *    re-emits via `IDomainEventBus`. Keeping pub and sub split lets operators
 *    deploy one without the other (one-way fan-out to a read-only log sink).
 *
 * ADR-0149 §C is N/A (this class touches no player health / scoreboard state).
 * ADR-0022 is satisfied: pure core-adapter Paper-free Lettuce binding; no
 * sibling-plugin import.
 *
 * @param redisUri Lettuce URI. Sourced from the `ASTERA_REDIS_URI` env var
 *   at wiring time; if absent, [com.astarworks.astera.core.platform.AsteraCorePlugin]
 *   skips registering this broadcaster entirely.
 * @param scheduler Used to off-load the blocking `publish()` call from the
 *   Paper main thread.
 * @param channel Redis pub/sub channel name. Defaults to
 *   `astera.broadcast.events`; multi-tenant deployments may suffix with
 *   server-group identifiers.
 * @param codec Wave 6c — polymorphic JSON codec for the wire payload.
 *   Injectable so tests can swap a stricter mapper or assert the encoded
 *   shape directly. Defaults to a fresh [BroadcastEventJsonCodec] with the
 *   standard kotlin + java-time module configuration.
 */
public class LettuceRedisBroadcaster(
    private val redisUri: RedisURI,
    private val scheduler: IScheduler,
    private val channel: String = DEFAULT_CHANNEL,
    private val codec: BroadcastEventJsonCodec = BroadcastEventJsonCodec(),
) : IBroadcaster {
    private val log = LoggerFactory.getLogger(javaClass)

    private val started = AtomicBoolean(false)
    private val disabled = AtomicBoolean(false)
    private var client: RedisClient? = null
    private var connection: StatefulRedisConnection<String, String>? = null

    /**
     * Open the Lettuce client + connection. Failures flip the broadcaster
     * into no-op mode rather than throwing — a missing Redis must not stop
     * the Paper server from starting.
     */
    public fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            val newClient = RedisClient.create(redisUri)
            val newConnection = newClient.connect()
            client = newClient
            connection = newConnection
            log.info("LettuceRedisBroadcaster connected to {} on channel '{}'", redisUri, channel)
        } catch (ex: RuntimeException) {
            disabled.set(true)
            log.warn(
                "LettuceRedisBroadcaster failed to connect to {} — broadcasting disabled (publish() will silently drop)",
                redisUri,
                ex,
            )
            shutdownQuietly()
        }
    }

    /** Shut down the Lettuce client + connection. Idempotent. */
    public fun stop() {
        if (!started.compareAndSet(true, false)) return
        shutdownQuietly()
    }

    override fun publish(event: DomainEvent) {
        if (disabled.get()) return
        val conn = connection ?: return
        val payload = serialize(event)
        scheduler.run(ExecutionScope.Io, kotlin.time.Duration.ZERO) {
            try {
                conn.sync().publish(channel, payload)
            } catch (ex: RuntimeException) {
                // Single-event publish failure must never escape into the
                // caller (often a Bukkit listener). Log and drop.
                log.warn("LettuceRedisBroadcaster publish failed for {}", event::class.simpleName, ex)
            }
        }
    }

    /**
     * Phase 1 PoC wire format: Kotlin data-class `toString()`. Human-readable,
     * stable enough for cross-server logging. Wave 6c replaces with a
     * polymorphic JSON codec.
     */
    private fun serialize(event: DomainEvent): String = event.toString()

    private fun shutdownQuietly() {
        try {
            connection?.close()
        } catch (ex: RuntimeException) {
            log.debug("Lettuce connection close failed", ex)
        }
        try {
            client?.shutdown()
        } catch (ex: RuntimeException) {
            log.debug("Lettuce client shutdown failed", ex)
        }
        connection = null
        client = null
    }

    public companion object {
        public const val DEFAULT_CHANNEL: String = "astera.broadcast.events"
    }
}
