package com.astarworks.astera.bots

import com.astarworks.astera.core.application.bots.BotState
import com.astarworks.astera.e2e.Bot
import com.astarworks.astera.e2e.Endpoint
import com.astarworks.astera.e2e.PackPolicy
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One bot's life (ADR-0247): log in to the lobby backend as a Velocity-forwarded player, queue for the lane, follow the
 * proxy `Connect` hop to the game backend, fight with [BotBrain], follow the hop back and disconnect.
 *
 * The bot connects to the backends directly and answers Paper's modern-forwarding query with the shared secret (the
 * production Velocity is online-mode and would refuse an account without a Mojang session); everything after login is
 * what a real client does.
 */
class BotSession(
    val name: String,
    private val lane: String,
    private val config: RunnerConfig,
    private val objectives: ObjectiveSource?,
    private val group: Group,
    private val onEnd: () -> Unit,
) {
    /** The bots of one request: the request is `queued` once every one of them has joined the queue. */
    class Group(
        private val requestId: String,
        size: Int,
        private val publish: (String, BotState, List<String>, String) -> Unit,
    ) {
        @Volatile var names: List<String> = emptyList()
        private val pending = AtomicInteger(size)
        private val failedOnce = AtomicBoolean(false)

        fun queued() {
            if (pending.decrementAndGet() == 0 && !failedOnce.get()) publish(requestId, BotState.QUEUED, names, "")
        }

        fun failed(reason: String) {
            if (failedOnce.compareAndSet(false, true)) publish(requestId, BotState.FAILED, names, reason)
        }
    }

    private val log = LoggerFactory.getLogger("BotSession")
    @Volatile private var stopReason: String? = null

    fun stop(reason: String) {
        stopReason = reason
    }

    fun run() {
        var bot: Bot? = null
        var brain: BotBrain? = null
        var queued = false
        try {
            val lobby = Endpoint(config.lobbyHost, config.port, config.forwardingSecret)
            val game = Endpoint(config.gameHost, config.port, config.forwardingSecret)
            val servers = mapOf("lobby" to lobby, "game" to game)
            bot = Bot.join(name, PackPolicy.LOAD, endpoint = lobby, servers = servers, waitForJoin = true, exactName = name)
            if (!bot.joined) {
                group.failed("$name could not join the lobby: ${bot.disconnectReason ?: "no answer"}")
                return
            }
            // The lobby kit and sidebar arrive a moment after the first position packet.
            bot.waitUntil(15_000) { bot.sidebarLines().isNotEmpty() && bot.hotbar().any { it != -1 } }
            Thread.sleep(1_500)
            val chatBefore = bot.chat.size
            bot.command("astera queue join $lane")
            bot.waitUntil(6_000) { bot.chat.size > chatBefore }
            log.info("{} queued for {}: {}", name, lane, bot.chat.drop(chatBefore).joinToString(" | ") { it.text }.take(200))
            queued = true
            group.queued()

            brain = BotBrain(bot, config.skill, objectives).start()
            playUntilBackInLobby(bot, brain)
        } catch (t: Throwable) {
            log.warn("{} crashed: {}", name, t.toString())
            if (!queued) group.failed("$name failed: $t")
        } finally {
            log.info("{} done{}", name, brain?.let { " (${it.summary()})" }.orEmpty())
            brain?.close()
            runCatching { bot?.close() }
            onEnd()
        }
    }

    private fun playUntilBackInLobby(bot: Bot, brain: BotBrain) {
        val startedAt = System.currentTimeMillis()
        var sawGame = false
        var backSince = 0L
        var goneSince = 0L
        while (stopReason == null) {
            val now = System.currentTimeMillis()
            val onGame = bot.endpoint.host == config.gameHost
            brain.mode = if (onGame && bot.joined) BotBrain.Mode.FIGHT else BotBrain.Mode.IDLE
            if (onGame && bot.joined) sawGame = true
            // Back on the lobby after a match: stay a few seconds for the result screen and rewards, then leave.
            if (sawGame && !onGame && bot.joined) {
                if (backSince == 0L) backSince = now else if (now - backSince > RESULT_LINGER_MS) return
            } else {
                backSince = 0L
            }
            // Kicked / connection lost (a hop resets the reason, so a set reason without a login is real).
            if (bot.disconnectReason != null && !bot.loggedIn && !bot.joined) {
                if (goneSince == 0L) goneSince = now else if (now - goneSince > 8_000) { log.info("{} disconnected: {}", name, bot.disconnectReason); return }
            } else {
                goneSince = 0L
            }
            if (!sawGame && now - startedAt > config.queueTimeoutMinutes * 60_000) { log.info("{} queue timeout, leaving", name); return }
            if (now - startedAt > MAX_LIFETIME_MS) { log.info("{} lifetime limit, leaving", name); return }
            Thread.sleep(500)
        }
        log.info("{} stopping: {}", name, stopReason)
    }

    private companion object {
        const val RESULT_LINGER_MS = 6_000L
        const val MAX_LIFETIME_MS = 40 * 60_000L
    }
}
