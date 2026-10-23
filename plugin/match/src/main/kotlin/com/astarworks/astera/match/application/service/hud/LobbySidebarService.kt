package com.astarworks.astera.match.application.service.hud

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.application.port.outbound.matchmaking.IQueueProgress
import com.astarworks.astera.match.domain.matchmaking.QueueSettings
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.domain.matchmaking.QueueEntry
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The lobby sidebar's single owner (WP14, ADR-0177): players online, the
 * player's queue status (mode, position, estimated wait) and wallet.
 *
 * Replaces both the never-wired `LobbyWaitingRoomHud` (own boss bar + own
 * scoreboard, bypassing the HUD gateway) and core's population sidebar, which
 * fought over the same region.
 *
 * A player belongs to the lobby when they are online and **not** in a live
 * match (sessions do not flip to `InMatch`, so the match repository is the
 * source of truth); players in a match are the [MatchSidebarService]'s. The
 * poll hands the region back and forth by itself, and the last-sent cache is
 * dropped whenever a player leaves the lobby so the lobby layout is redrawn
 * the next time they return.
 *
 * "Online" is tracked here from [onJoined] / [onQuit] (the wiring forwards
 * `PlayerJoinedServer` / `PlayerQuitServer`), **not** read from
 * `IPlayerSessionRepository`: with the Postgres overlay (priority 100)
 * registered before this plugin enables, that port resolves to a different
 * store than the in-memory one Core's join listener writes, so the poll saw
 * nobody and the sidebar never appeared on the live lobby backend.
 */
public class LobbySidebarService(
    private val matches: IMatchRepository,
    private val matchmaker: IMatchmaker,
    private val hud: IHudGateway,
    private val messages: IMessageRenderer,
    private val stars: (PlayerId) -> Long?,
    scheduler: IScheduler,
    private val gold: (PlayerId) -> Long? = { null },
    pollPeriodTicks: Long = DEFAULT_POLL_PERIOD_TICKS,
    private val settings: () -> QueueSettings = { QueueSettings.DEFAULT },
    private val progress: () -> IQueueProgress? = { null },
    private val clock: () -> Instant = { Instant.now() },
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val failureLogged: MutableSet<PlayerId> = mutableSetOf()
    private val lastSent: MutableMap<PlayerId, HudLayout.Sidebar> = mutableMapOf()
    private val online: MutableSet<PlayerId> = ConcurrentHashMap.newKeySet()

    private val taskHandle: IScheduler.TaskHandle = scheduler.runRepeatingTicks(
        initialDelayTicks = pollPeriodTicks,
        periodTicks = pollPeriodTicks,
    ) {
        refreshAll()
    }

    /** Cancel the poll. Idempotent. */
    public fun stop() {
        taskHandle.cancel()
    }

    /** A player arrived on this server: track and draw them, and refresh everyone's online count. */
    public fun onJoined(playerId: PlayerId) {
        online += playerId
        refreshAll()
    }

    /** A player left this server: forget them and refresh everyone's online count. */
    public fun onQuit(playerId: PlayerId) {
        online -= playerId
        lastSent.remove(playerId)
        failureLogged.remove(playerId)
        refreshAll()
    }

    /** Re-evaluate every online player. */
    public fun refreshAll() {
        val now = online.toList()
        lastSent.keys.retainAll(now.toSet())
        for (playerId in now) refresh(playerId, now.size)
    }

    /** Re-draw one player (queue or wallet change). */
    public fun refresh(playerId: PlayerId) {
        if (playerId !in online) return
        refresh(playerId, online.size)
    }

    private fun refresh(playerId: PlayerId, onlineCount: Int) {
        val match = matches.findMatchOf(playerId)
        if (match != null && match.phase != MatchPhase.ENDED) {
            lastSent.remove(playerId)
            return
        }
        val layout = layoutFor(playerId, onlineCount)
        showQueueActionBar(playerId)
        if (lastSent[playerId] == layout) return
        lastSent[playerId] = layout
        when (val outcome = hud.showFor(playerId, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> {
                lastSent.remove(playerId)
                if (failureLogged.add(playerId)) log.warn("Lobby sidebar show failed for {}: {}", playerId, outcome.error)
            }
        }
    }

    private fun layoutFor(playerId: PlayerId, onlineCount: Int): HudLayout.Sidebar {
        val entry = matchmaker.queueStatus(playerId)
        val lines = buildList {
            add(text("astera.hud.lobby.online", "count" to onlineCount.toString()))
            add(text("astera.hud.sidebar.spacer"))
            if (entry == null) {
                add(text("astera.hud.lobby.queue.idle"))
            } else {
                // ADR-0248: lane, players X/min, elapsed time, and what we are waiting for.
                val wait = waitingState(entry)
                add(text("astera.hud.lobby.queue.mode", "mode" to modeLabel(playerId, entry.mode)))
                add(text("astera.hud.lobby.queue.players", "players" to wait.queued.toString(), "required" to wait.required.toString()))
                add(text("astera.hud.lobby.queue.elapsed", "time" to wait.elapsed))
                add(text(wait.statusKey, "seconds" to (wait.seconds ?: 0).toString()))
            }
            stars(playerId)?.let {
                add(text("astera.hud.sidebar.spacer"))
                add(text("astera.hud.sidebar.stars", "stars" to it.toString()))
                // GOLD (quests / top-tier shop items) sits under STAR; hidden while the player has none.
                gold(playerId)?.takeIf { g -> g > 0L }?.let { g -> add(text("astera.hud.sidebar.gold", "gold" to g.toString())) }
            }
        }
        return HudLayout.Sidebar(titleKey = TITLE_KEY, lines = lines)
    }

    /** What the queued player is shown (ADR-0248). */
    private data class WaitingState(
        val queued: Int,
        val required: Int,
        val elapsed: String,
        val statusKey: String,
        val seconds: Int?,
    )

    private fun waitingState(entry: QueueEntry): WaitingState {
        val lane = settings().laneFor(entry.mode)
        val queued = matchmaker.pendingByMode(entry.mode).size
        val required = lane?.minPlayers ?: queued
        val seconds = Duration.between(entry.enqueuedAt, clock()).seconds.coerceAtLeast(0)
        val laneProgress = lane?.let { progress()?.progressOf(it.key) }
        val statusKey = when {
            laneProgress?.countdownSecondsLeft != null -> "astera.hud.lobby.queue.status.starting"
            laneProgress?.held == true -> "astera.hud.lobby.queue.status.busy"
            else -> "astera.hud.lobby.queue.status.waiting"
        }
        return WaitingState(
            queued = queued,
            required = required,
            elapsed = "%d:%02d".format(seconds / 60, seconds % 60),
            statusKey = statusKey,
            seconds = laneProgress?.countdownSecondsLeft,
        )
    }

    /** The always-visible one-liner above the hotbar while queued (ADR-0248). Expires by itself once the player leaves. */
    private fun showQueueActionBar(playerId: PlayerId) {
        val entry = matchmaker.queueStatus(playerId) ?: return
        val wait = waitingState(entry)
        val suffix = wait.statusKey.substringAfterLast('.')
        val bar = HudLayout.ActionBar(
            component = text(
                "astera.hud.lobby.queue.actionbar.$suffix",
                "lane" to modeLabel(playerId, entry.mode),
                "players" to wait.queued.toString(),
                "required" to wait.required.toString(),
                "time" to wait.elapsed,
                "seconds" to (wait.seconds ?: 0).toString(),
            ),
            ttlTicks = ACTION_BAR_TTL_TICKS,
            priority = 1,
        )
        hud.showFor(playerId, bar)
    }

    private fun modeLabel(viewer: PlayerId, mode: MatchmakingMode): String {
        val id = when (mode) {
            is MatchmakingMode.Norma -> "norma"
            is MatchmakingMode.Custom -> mode.adminConfigured.value
            is MatchmakingMode.Tournament -> return "Tournament"
        }
        val key = MessageKey("astera.gamemode.$id.title")
        val rendered = messages.render(viewer, key)
        return if (rendered == key.value) id else rendered
    }

    private fun text(key: String, vararg placeholders: Pair<String, String>): HudComponent.Text =
        HudComponent.Text(MessageKey(key), placeholders = placeholders.toMap())

    public companion object {
        public const val DEFAULT_POLL_PERIOD_TICKS: Long = 20L
        private const val ACTION_BAR_TTL_TICKS: Int = 30
        private val TITLE_KEY: MessageKey = MessageKey("astera.hud.lobby.title")
    }
}
