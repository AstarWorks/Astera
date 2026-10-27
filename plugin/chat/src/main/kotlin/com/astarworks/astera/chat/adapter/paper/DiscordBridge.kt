package com.astarworks.astera.chat.adapter.paper

import com.astarworks.astera.chat.application.config.ChatFrame
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.random.Random

/** One owner backend, one serial lifecycle. Gateway v10 JSON, no compression or SDK dependency. */
public class DiscordBridge(
    private val http: HttpClient,
    private val token: String,
    private val channel: String,
    private val webhook: String?,
    private val plainLine: (String, ChatFrame) -> String,
    private val inbound: (String, String) -> Unit,
    private val log: Logger,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "astera-chat-discord").apply { isDaemon = true } }
    @Volatile private var closed = false
    private var generation = 0
    private var socket: WebSocket? = null
    private var heartbeat: ScheduledFuture<*>? = null
    private var ack = true
    private var sequence: Long? = null
    private var session: String? = null
    private var gateway = "wss://gateway.discord.gg"
    private var backoff = 1L
    private val outgoing = ArrayDeque<JsonObject>()
    private var sending = false

    public fun start() { execute { connect() } }

    public fun send(origin: String, frame: ChatFrame) {
        val content = plainLine(origin, frame).take(2000)
        val body = buildJsonObject {
            put("content", content)
            put("allowed_mentions", buildJsonObject { put("parse", JsonArray(emptyList())) })
            if (webhook != null) {
                put("username", frame.name.take(80))
                if (frame.playerId.matches(Regex("[0-9a-f-]{36}"))) put("avatar_url", "https://mc-heads.net/avatar/${frame.playerId}")
            }
        }
        execute {
            if (outgoing.size >= 100) log.warning("Discord chat queue full; dropping a message")
            else { outgoing.addLast(body); if (!sending) pump() }
        }
    }

    private fun execute(block: () -> Unit) {
        if (!closed) runCatching { executor.execute { if (!closed) block() } }
    }

    private fun connect() {
        if (closed) return
        val current = ++generation
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
            .buildAsync(URI.create("$gateway/?v=10&encoding=json"), object : WebSocket.Listener {
                private val fragments = StringBuilder()
                override fun onOpen(webSocket: WebSocket) {
                    execute { if (generation == current) socket = webSocket else webSocket.abort() }
                    webSocket.request(1)
                }
                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    if (fragments.length + data.length > 1_048_576) {
                        webSocket.abort(); execute { reconnect(current) }; return null
                    }
                    fragments.append(data)
                    if (last) {
                        val text = fragments.toString(); fragments.setLength(0)
                        execute {
                            if (current == generation) runCatching { receive(text, current) }
                                .onFailure { log.warning("Malformed Discord gateway payload dropped") }
                        }
                    }
                    webSocket.request(1)
                    return null
                }
                override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                    execute {
                        if (current == generation) {
                            if (statusCode in setOf(4004, 4010, 4011, 4012, 4013, 4014)) {
                                log.warning("Discord gateway disabled (close $statusCode); check bot token and Message Content intent")
                                heartbeat?.cancel(false); generation++; socket = null
                            } else {
                                if (statusCode == 4007 || statusCode == 4009) { session = null; sequence = null }
                                reconnect(current)
                            }
                        }
                    }
                    return null
                }
                override fun onError(webSocket: WebSocket, error: Throwable) { execute { reconnect(current) } }
            }).whenComplete { _, error -> if (error != null) execute { reconnect(current) } }
    }

    private fun receive(text: String, current: Int) {
        val event = Json.parseToJsonElement(text).jsonObject
        val data = event["d"]
        when (event["op"]?.jsonPrimitive?.intOrNull) {
            10 -> {
                val interval = data!!.jsonObject.getValue("heartbeat_interval").jsonPrimitive.long.coerceAtLeast(1000)
                ack = true
                heartbeat?.cancel(false)
                heartbeat = executor.scheduleAtFixedRate({
                    if (closed || current != generation) return@scheduleAtFixedRate
                    if (!ack) reconnect(current) else beat()
                }, Random.nextLong(interval), interval, TimeUnit.MILLISECONDS)
                if (session != null && sequence != null) {
                    sendEvent(6, buildJsonObject { put("token", token); put("session_id", session); put("seq", sequence) })
                } else {
                    sendEvent(2, buildJsonObject {
                        put("token", token); put("intents", 512 + 32768)
                        put("properties", buildJsonObject { put("os", "linux"); put("browser", "AsteraChat"); put("device", "AsteraChat") })
                    })
                }
            }
            11 -> ack = true
            1 -> beat()
            7 -> reconnect(current)
            9 -> {
                if (data?.jsonPrimitive?.booleanOrNull != true) { session = null; sequence = null }
                reconnect(current, 5)
            }
            0 -> {
                val next = event["s"]?.jsonPrimitive?.longOrNull
                if (next != null && sequence != null && next <= sequence!!) return
                if (next != null) sequence = next
                when (event["t"]?.jsonPrimitive?.content) {
                    "READY" -> {
                        val ready = data!!.jsonObject
                        session = ready.getValue("session_id").jsonPrimitive.content
                        ready["resume_gateway_url"]?.jsonPrimitive?.content?.let { url ->
                            if (URI.create(url).let { it.scheme == "wss" && it.host.endsWith(".discord.gg") }) gateway = url.trimEnd('/')
                        }
                        backoff = 1
                        log.info("Discord chat bridge connected")
                    }
                    "RESUMED" -> backoff = 1
                    "MESSAGE_CREATE" -> {
                        val message = data!!.jsonObject
                        val author = message["author"]?.jsonObject ?: return
                        if (message["channel_id"]?.jsonPrimitive?.content != channel ||
                            message["webhook_id"] != null || author["bot"]?.jsonPrimitive?.booleanOrNull == true) return
                        val content = message["content"]?.jsonPrimitive?.content.orEmpty()
                            .replace(Regex("[\\r\\n\\p{Cc}]"), " ").take(2000)
                        val name = message["member"]?.jsonObject?.get("nick")?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                            ?: author["global_name"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                            ?: author["username"]?.jsonPrimitive?.content ?: "Discord"
                        if (content.isNotBlank()) inbound(name.replace(Regex("[\\r\\n\\p{Cc}]"), " "), content)
                    }
                }
            }
        }
    }

    private fun beat() { ack = false; sendEvent(1, sequence?.let { JsonPrimitive(it) } ?: JsonNull) }

    private fun sendEvent(op: Int, data: JsonElement) {
        val current = generation
        socket?.sendText(buildJsonObject { put("op", op); put("d", data) }.toString(), true)
            ?.whenComplete { _, error -> if (error != null) execute { reconnect(current) } }
    }

    private fun reconnect(current: Int, minimumDelay: Long = 1) {
        if (closed || current != generation) return
        generation++
        heartbeat?.cancel(false); heartbeat = null
        socket?.abort(); socket = null
        log.warning("Discord gateway disconnected; reconnecting")
        val delay = maxOf(minimumDelay, backoff)
        backoff = (backoff * 2).coerceAtMost(60)
        executor.schedule({ connect() }, delay, TimeUnit.SECONDS)
    }

    /** Bounded FIFO, one HTTP request at a time; honour Discord's retry_after on 429. */
    private fun pump() {
        if (closed) return
        val body = outgoing.firstOrNull() ?: run { sending = false; return }
        sending = true
        val uri = webhook ?: "https://discord.com/api/v10/channels/$channel/messages"
        val builder = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        if (webhook == null) builder.header("Authorization", "Bot $token")
        http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).whenComplete { response, error ->
            execute {
                var delay = 500L
                if (error == null && response.statusCode() == 429) {
                    val retry = runCatching { Json.parseToJsonElement(response.body()).jsonObject["retry_after"]!!.jsonPrimitive.double }.getOrDefault(5.0)
                    delay = (retry.coerceIn(0.5, 120.0) * 1000).toLong()
                } else {
                    outgoing.removeFirst()
                    if (error != null || response.statusCode() !in 200..299) log.warning("Discord outbound message failed; message dropped")
                }
                executor.schedule({ pump() }, delay, TimeUnit.MILLISECONDS)
            }
        }
    }

    override fun close() {
        closed = true
        socket?.abort()
        executor.shutdownNow()
    }
}
