package com.astarworks.astera.core.adapter.paper.broadcast

import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.domain.event.DomainEvent
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Operator-visibility [IBroadcaster] that posts [DomainEvent] summaries to a
 * Discord channel via incoming-webhook POST.
 *
 * Role contrast (Wave 6b-3 / ADR-0158 fan-out):
 *
 *  - [com.astarworks.astera.core.adapter.paper.event.PaperBroadcaster] —
 *    in-process logger (Phase 1 default).
 *  - [LettuceRedisBroadcaster] — cross-server pub/sub fan-out.
 *  - [DiscordWebhookBroadcaster] — pushes human-readable notifications into a
 *    Discord channel so operators see lifecycle events (match start / end,
 *    nexus destroyed, anticheat action) without tailing logs.
 *
 * Filter scope: the [IBroadcaster] port has no per-event scope hint
 * (see [com.astarworks.astera.core.application.port.outbound.IBroadcaster]),
 * so this class publishes every event it receives. Operators compose a
 * filtering broadcaster (e.g. "only `MatchStarted` and `NexusDestroyed`")
 * in [com.astarworks.astera.core.platform.AsteraCorePlugin] wiring; that
 * is wiring-layer responsibility, not this adapter's.
 *
 * Reliability posture (fire-and-forget):
 *  - HTTP is dispatched via [HttpClient.sendAsync]. The webhook response is
 *    observed only for logging; the calling thread (Paper main) does not
 *    block on the round-trip.
 *  - Any failure (DNS, 5xx, connection reset) is logged at WARN and dropped.
 *    A flaky Discord must not stall the tick loop nor surface back into
 *    application code.
 *  - This adapter never throws from [publish]; callers can ignore failure.
 *
 * Rate-limit guard (Wave 6c / ADR-0160):
 *  - Token-bucket sized to Discord's per-channel quota: capacity 30, refill
 *    30 tokens per minute (one token every 2000 ms). Tokens are refilled
 *    lazily on each [publish] call from the elapsed-time delta — no
 *    background scheduler. When the bucket is empty the event is dropped
 *    with a WARN log (with a running drop counter for visibility); the
 *    calling thread is never blocked.
 *  - Discord 429 responses still flow through the existing async-response
 *    handler below; the token bucket is a pre-emptive guard so the typical
 *    busy match never reaches the 429 path. A 5xx outage that lasts long
 *    enough to drain queued requests would tick tokens back to capacity
 *    via elapsed-time refill, so the broadcaster self-recovers.
 *
 * Phase 1 PoC limitations:
 *  - Message body is the bare `event.toString()` (Kotlin data-class default).
 *    Adequate for operator visibility; Wave 6c will introduce a
 *    `DiscordEventRenderer` that formats per-variant embeds.
 *  - No retries on 5xx. A transient Discord outage drops events.
 *
 * ADR-0149 §C is N/A. ADR-0022 is satisfied: core-adapter, no Paper API
 * touched, no sibling-plugin import. JDK-bundled [HttpClient] (no extra
 * Gradle dep).
 *
 * @param webhookUrl Discord-issued incoming-webhook URL. Sourced from the
 *   `ASTERA_DISCORD_WEBHOOK_URL` env var at wiring time; if absent,
 *   [com.astarworks.astera.core.platform.AsteraCorePlugin] skips registering
 *   this broadcaster entirely.
 * @param httpClient Defaults to [HttpClient.newHttpClient]; injectable for
 *   tests that want to capture outgoing requests.
 */
public class DiscordWebhookBroadcaster(
    private val webhookUrl: String,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
    private val nowNanos: () -> Long = { System.nanoTime() },
) : IBroadcaster {
    private val log = LoggerFactory.getLogger(javaClass)

    private val webhookUri: URI? = runCatching { URI.create(webhookUrl) }
        .onFailure { ex ->
            log.warn(
                "DiscordWebhookBroadcaster received malformed webhook URL; broadcaster disabled: {}",
                webhookUrl,
                ex,
            )
        }
        .getOrNull()

    /**
     * Available token count, in [0, RATE_LIMIT_CAPACITY]. Decremented per
     * accepted publish; refilled lazily by [tryAcquireToken] based on the
     * delta between [nowNanos] and [lastRefillNanos].
     */
    private val tokens: AtomicInteger = AtomicInteger(RATE_LIMIT_CAPACITY)

    /** Last clock reading used to credit refill tokens. */
    private val lastRefillNanos: AtomicLong = AtomicLong(nowNanos())

    /** Running count of events dropped because the bucket was empty. */
    private val droppedCount: AtomicLong = AtomicLong(0L)

    override fun publish(event: DomainEvent) {
        val uri = webhookUri ?: return
        if (!tryAcquireToken()) {
            val drops = droppedCount.incrementAndGet()
            log.warn(
                "Discord webhook rate-limit guard dropped {} (bucket empty; total drops since start={})",
                event::class.simpleName,
                drops,
            )
            return
        }
        val payload = renderPayload(event)
        val request = HttpRequest.newBuilder()
            .uri(uri)
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .whenComplete { response, ex ->
                when {
                    ex != null -> log.warn(
                        "Discord webhook POST failed for {}",
                        event::class.simpleName,
                        ex,
                    )
                    response.statusCode() == 429 -> log.warn(
                        "Discord webhook rate-limited (429) for {}; body: {}",
                        event::class.simpleName,
                        response.body(),
                    )
                    response.statusCode() !in 200..299 -> log.warn(
                        "Discord webhook returned {} for {}; body: {}",
                        response.statusCode(),
                        event::class.simpleName,
                        response.body(),
                    )
                }
            }
    }

    /**
     * Build the JSON payload Discord's incoming-webhook API accepts.
     *
     * Phase 1 PoC: `{"content": "<event.toString()>"}`. Single hand-rolled
     * JSON object — no serialization library dep needed for one string field,
     * and avoids dragging `kotlinx.serialization` polymorphism into this
     * adapter prematurely. Wave 6c replaces with a per-variant embed renderer.
     */
    private fun renderPayload(event: DomainEvent): String {
        val rendered = event.toString()
        // Discord caps `content` at 2000 chars.
        val truncated = if (rendered.length > MAX_CONTENT_LEN) {
            rendered.substring(0, MAX_CONTENT_LEN - TRUNCATION_MARK.length) + TRUNCATION_MARK
        } else {
            rendered
        }
        return "{\"content\":\"${escapeJsonString(truncated)}\"}"
    }

    /**
     * Minimal JSON string escaper. Covers the characters the JSON RFC
     * requires escaping inside a string literal. Sufficient for Phase 1:
     * `event.toString()` output is ASCII-dominant Kotlin data-class prose,
     * and Discord's parser is JSON-strict. Control chars below U+0020
     * (including form-feed and backspace) fall through to the `\uXXXX`
     * branch rather than relying on Kotlin char-literal escapes (Kotlin
     * does not accept `'\f'` in a char literal).
     */
    private fun escapeJsonString(input: String): String {
        val sb = StringBuilder(input.length + 16)
        for (ch in input) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u").append("%04x".format(ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    /**
     * Attempt to consume one token. Refills the bucket lazily based on
     * elapsed `nowNanos()` time before the check, so a long idle period
     * fully replenishes capacity without a background scheduler.
     *
     * Concurrency: refill uses [AtomicLong.compareAndSet] so only one
     * concurrent caller commits the refill computation per window;
     * decrement uses [AtomicInteger.compareAndSet] for the same reason.
     * A failed CAS retries the read-modify-write cycle.
     */
    private fun tryAcquireToken(): Boolean {
        refillIfDue()
        while (true) {
            val current = tokens.get()
            if (current <= 0) return false
            if (tokens.compareAndSet(current, current - 1)) return true
        }
    }

    private fun refillIfDue() {
        val now = nowNanos()
        val last = lastRefillNanos.get()
        val elapsedNanos = now - last
        if (elapsedNanos < REFILL_INTERVAL_NANOS) return
        val newTokens = (elapsedNanos / REFILL_INTERVAL_NANOS).toInt()
        if (newTokens <= 0) return
        val nextLast = last + newTokens * REFILL_INTERVAL_NANOS
        if (!lastRefillNanos.compareAndSet(last, nextLast)) {
            // Another thread committed the refill; let it credit the bucket.
            return
        }
        while (true) {
            val current = tokens.get()
            val target = (current + newTokens).coerceAtMost(RATE_LIMIT_CAPACITY)
            if (current == target) return
            if (tokens.compareAndSet(current, target)) return
        }
    }

    public companion object {
        private const val USER_AGENT: String = "Astera-DiscordWebhookBroadcaster/1.0"
        private const val MAX_CONTENT_LEN: Int = 2000
        private const val TRUNCATION_MARK: String = "...[truncated]"

        /** Discord's per-channel webhook quota (30 req / minute). */
        public const val RATE_LIMIT_CAPACITY: Int = 30

        /** Refill cadence: 1 token every 2000 ms = 30 tokens / minute. */
        public const val REFILL_INTERVAL_MS: Long = 2_000L

        private const val REFILL_INTERVAL_NANOS: Long = REFILL_INTERVAL_MS * 1_000_000L
    }
}
