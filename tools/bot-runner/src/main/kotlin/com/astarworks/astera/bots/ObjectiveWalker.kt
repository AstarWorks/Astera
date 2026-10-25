package com.astarworks.astera.bots

import com.astarworks.astera.e2e.Bot
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode

/**
 * Deterministic, token-free objective driver for one protocol bot: it walks to a stage point along the A*
 * [Navigator] (island/bridge route graph first, then packet-validated cell paths) and holds a position, one
 * position packet per client tick, exactly like [BotBrain] does while it travels. No fighting, no randomness.
 *
 * The e2e playthroughs of the objective modes (carry-tnt, capture-wool, steal-jewelry) use it to reach a mode's
 * regions the way a player would walk to them, so the server sees real region transitions.
 *
 * [periodic] runs about every [PERIODIC_MS] on the walking thread (the e2e uses it to keep sending
 * `astera norma choose kill`, so a star pickup on the way cannot level the bot up to a Norma win before the objective).
 */
class ObjectiveWalker(
    private val bot: Bot,
    objectives: ObjectiveSource?,
    private val speed: Double = 0.23,
    private val periodic: (() -> Unit)? = null,
) {
    enum class Result { ARRIVED, TIMEOUT, STOPPED }

    private val nav = Navigator(bot.terrain, objectives?.routeGraph())
    private var screensSeen = bot.screens.size
    private var lastRespawnAt = 0L
    private var lastPeriodicAt = 0L
    private val lastKeepAlive = HashMap<Bot, Long>()

    /** Walks to ([x], [z]) until within [arrive] blocks, [timeoutMs] passes or [stop] turns true. */
    fun walkTo(
        x: Double,
        z: Double,
        arrive: Double = 1.0,
        timeoutMs: Long = 120_000,
        keepAlive: List<Bot> = emptyList(),
        stop: () -> Boolean = { false },
    ): Result {
        val end = System.currentTimeMillis() + timeoutMs
        nav.clear()
        while (true) {
            val tickStart = System.nanoTime()
            val now = System.currentTimeMillis()
            if (stop()) return Result.STOPPED
            if (now > end) return Result.TIMEOUT
            if (alive(now)) {
                when (nav.follow(bot, x, z, null, arrive, speed, now, null, 0f)) {
                    Navigator.Status.ARRIVED -> return Result.ARRIVED
                    Navigator.Status.MOVING -> Unit
                    Navigator.Status.NO_PATH -> bot.nudge() // chunks still loading or a blocked cell: stand and replan
                }
            }
            service(now, keepAlive)
            pace(tickStart)
        }
    }

    /** Stands still (one position packet per tick) until [until] holds; false on [timeoutMs]. */
    fun hold(timeoutMs: Long, keepAlive: List<Bot> = emptyList(), until: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val tickStart = System.nanoTime()
            val now = System.currentTimeMillis()
            if (until()) return true
            if (now > end) return false
            if (alive(now)) bot.nudge()
            service(now, keepAlive)
            pace(tickStart)
        }
    }

    /** False while the bot is dead (presses Respawn) or spectating (death cam); closes server-opened windows. */
    private fun alive(now: Long): Boolean {
        val seen = bot.screens.size
        if (seen < screensSeen) screensSeen = 0
        if (seen != screensSeen) {
            val last = bot.screens.lastOrNull()
            screensSeen = seen
            if (last != null) bot.closeWindow(last.containerId)
        }
        if (bot.health == 0f) {
            if (now - lastRespawnAt > 1_000) { lastRespawnAt = now; bot.respawn() }
            return false
        }
        if (bot.gameMode == GameMode.SPECTATOR) { bot.nudge(); return false }
        return true
    }

    private fun service(now: Long, keepAlive: List<Bot>) {
        for (other in keepAlive) {
            if (now - (lastKeepAlive[other] ?: 0L) >= KEEP_ALIVE_MS) { lastKeepAlive[other] = now; other.nudge() }
        }
        if (periodic != null && now - lastPeriodicAt >= PERIODIC_MS) { lastPeriodicAt = now; periodic.invoke() }
    }

    private fun pace(tickStart: Long) {
        val spent = (System.nanoTime() - tickStart) / 1_000_000
        if (spent < TICK_MS) Thread.sleep(TICK_MS - spent)
    }

    private companion object {
        const val TICK_MS = 50L
        const val KEEP_ALIVE_MS = 500L
        const val PERIODIC_MS = 3_000L
    }
}
