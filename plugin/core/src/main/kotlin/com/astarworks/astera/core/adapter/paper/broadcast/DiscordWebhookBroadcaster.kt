package com.astarworks.astera.core.adapter.paper.broadcast

import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.domain.event.DomainEvent
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

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
 * Phase 1 PoC limitations (Wave 6b-3):
 *  - No rate-limit guard. Discord caps webhooks at 30 req/min per channel;
 *    busy waves (many `DamageDealt` per second) will hit 429s. Wiring is
 *    expected to install an event filter so high-frequency events never
 *    reach this adapter. Wave 6c will add a token-bucket guard + per-channel
 *    queue (see ADR-0158 §4 "discord rate-limit").
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

    override fun publish(event: DomainEvent) {
        val uri = webhookUri ?: return
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

    public companion object {
        private const val USER_AGENT: String = "Astera-DiscordWebhookBroadcaster/1.0"
        private const val MAX_CONTENT_LEN: Int = 2000
        private const val TRUNCATION_MARK: String = "...[truncated]"
    }
}
