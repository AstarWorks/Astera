package com.astarworks.astera.bots

import com.astarworks.astera.core.application.bots.BotChannels
import com.astarworks.astera.core.application.bots.BotRequest
import com.astarworks.astera.core.application.bots.BotStatus
import io.lettuce.core.RedisClient
import io.lettuce.core.pubsub.RedisPubSubAdapter
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/** Everything the runner reads from its environment (docker/docker-compose.yml `bot-runner`). */
data class RunnerConfig(
    val redisUri: String,
    val forwardingSecret: String,
    val lobbyHost: String,
    val gameHost: String,
    val port: Int,
    val maxBots: Int,
    val contentDir: File?,
    val queueTimeoutMinutes: Long,
    val skill: BotSkill,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): RunnerConfig = RunnerConfig(
            redisUri = env["ASTERA_REDIS_URI"] ?: "redis://redis:6379",
            forwardingSecret = env["VELOCITY_FORWARDING_SECRET"]?.takeIf { it.isNotBlank() }
                ?: error("VELOCITY_FORWARDING_SECRET is not set (the bots answer Velocity modern forwarding like the proxy does)"),
            lobbyHost = env["BOT_LOBBY_HOST"] ?: "lobby",
            gameHost = env["BOT_GAME_HOST"] ?: "game",
            port = env["BOT_BACKEND_PORT"]?.toInt() ?: 25565,
            maxBots = env["BOT_MAX"]?.toInt() ?: 8,
            contentDir = (env["BOT_CONTENT_DIR"] ?: "/content").let(::File).takeIf { it.isDirectory },
            queueTimeoutMinutes = env["BOT_QUEUE_TIMEOUT_MINUTES"]?.toLong() ?: 10,
            skill = BotSkill(
                aimErrorDeg = env["BOT_AIM_ERROR_DEG"]?.toFloat() ?: 4f,
                swingIntervalMs = env["BOT_SWING_MS"]?.toLong() ?: 700,
                shotIntervalMs = env["BOT_SHOT_MS"]?.toLong() ?: 450,
            ),
        )
    }
}

/** Account names of the form `Bot_Ash`; a name is busy while its bot is connected. */
class BotNames(private val prefix: String = BotChannels.NAME_PREFIX) {
    private val inUse = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    fun take(count: Int): List<String> {
        val out = ArrayList<String>()
        var round = 0
        while (out.size < count) {
            for (base in BASE) {
                if (out.size >= count) break
                val name = prefix + base + if (round == 0) "" else round.toString()
                if (name.length <= 16 && inUse.add(name)) out += name
            }
            round++
            if (round > 50) break
        }
        return out
    }

    fun release(name: String) {
        inUse.remove(name)
    }

    private companion object {
        val BASE = listOf("Ash", "Birch", "Cedar", "Dusk", "Ember", "Fern", "Gale", "Hazel", "Iris", "Juniper", "Kelp", "Linden", "Moss", "Nettle", "Oak", "Pine")
    }
}

/**
 * The bot runner service (ADR-0247): listens on [BotChannels.REQUEST], spawns that many bots (capped), reports on
 * [BotChannels.STATUS]. Each bot is a [BotSession]: it logs in as a Velocity-forwarded player, queues for the lane,
 * plays the match with [BotBrain] and disconnects once it is back in the lobby.
 */
class BotRunner(private val config: RunnerConfig) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val names = BotNames()
    private val active = ConcurrentHashMap<String, BotSession>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "bot-session").also { it.isDaemon = true } }
    private val client = RedisClient.create(config.redisUri)
    private val publisher = client.connect().sync()
    private val subscriber = client.connectPubSub()

    fun start() {
        subscriber.addListener(object : RedisPubSubAdapter<String, String>() {
            override fun message(channel: String, message: String) {
                if (channel == BotChannels.REQUEST) pool.execute { handle(message) }
            }
        })
        subscriber.sync().subscribe(BotChannels.REQUEST)
        log.info("bot-runner ready: listening on {}, cap {} bots, lobby={} game={}", BotChannels.REQUEST, config.maxBots, config.lobbyHost, config.gameHost)
    }

    private fun status(requestId: String, state: BotStatus.State, bots: List<String> = emptyList(), reason: String = "") {
        val json = BotStatus(requestId, state, bots, reason).toJson()
        runCatching { publisher.publish(BotChannels.STATUS, json) }.onFailure { log.warn("could not publish status: {}", it.toString()) }
        log.info("status {}", json)
    }

    private fun handle(message: String) {
        val request = runCatching { BotRequest.fromJson(message) }.getOrElse {
            log.warn("ignoring malformed bot request '{}': {}", message.take(200), it.toString())
            return
        }
        val id = request.requestId
        val lane = request.lane.trim()
        if (lane.isEmpty() || !lane.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            status(id, BotStatus.State.FAILED, reason = "invalid lane '${request.lane}'")
            return
        }
        if (request.count < 1) {
            status(id, BotStatus.State.FAILED, reason = "count must be at least 1")
            return
        }
        val group = BotSession.Group(id, request.count) { a, b, c, d -> status(a, b, c, d) }
        val objectives = config.contentDir?.let { StageObjectives.load(it, lane) }
        // Reserve names and slots atomically so two simultaneous requests cannot overshoot the cap.
        val sessions: List<BotSession> = synchronized(this) {
            val room = config.maxBots - active.size
            if (request.count > room) {
                emptyList()
            } else {
                val chosen = names.take(request.count)
                group.names = chosen
                chosen.map { name ->
                    BotSession(name, lane, config, objectives, group, onEnd = { active.remove(name); names.release(name) }).also { active[name] = it }
                }
            }
        }
        if (sessions.isEmpty()) {
            status(id, BotStatus.State.FAILED, reason = "bot cap reached: ${active.size}/${config.maxBots} running, ${request.count} requested")
            return
        }
        status(id, BotStatus.State.SPAWNING, group.names)
        sessions.forEach { s -> pool.execute { s.run() } }
    }

    override fun close() {
        active.values.forEach { it.stop("runner shutting down") }
        runCatching { subscriber.close() }
        runCatching { client.shutdown() }
        pool.shutdownNow()
    }
}

fun main() {
    val log = LoggerFactory.getLogger("BotRunner")
    val runner = try {
        BotRunner(RunnerConfig.fromEnv()).also { it.start() }
    } catch (t: Throwable) {
        log.error("bot-runner failed to start: {}", t.toString())
        exitProcess(1)
    }
    Runtime.getRuntime().addShutdownHook(Thread { runner.close() })
    Thread.currentThread().join()
}
