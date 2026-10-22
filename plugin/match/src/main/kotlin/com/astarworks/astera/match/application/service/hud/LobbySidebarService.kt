package com.astarworks.astera.match.application.service.hud

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.SessionState
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.domain.matchmaking.QueueEntry
import org.slf4j.LoggerFactory

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
 */
public class LobbySidebarService(
    private val sessions: IPlayerSessionRepository,
    private val matches: IMatchRepository,
    private val matchmaker: IMatchmaker,
    private val hud: IHudGateway,
    private val messages: IMessageRenderer,
    private val stars: (PlayerId) -> Long?,
    scheduler: IScheduler,
    pollPeriodTicks: Long = DEFAULT_POLL_PERIOD_TICKS,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val lastSent: MutableMap<PlayerId, HudLayout.Sidebar> = mutableMapOf()

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

    /** Re-evaluate every online player. */
    public fun refreshAll() {
        val online = sessions.all().filter { it.state != SessionState.Disconnected }
        lastSent.keys.retainAll(online.map { it.playerId }.toSet())
        for (session in online) {
            // Spectators / loading players keep whatever they have.
            if (session.state is SessionState.Spectator || session.state is SessionState.Loading) continue
            refresh(session.playerId, online.size)
        }
    }

    /** Re-draw one player (queue or wallet change). */
    public fun refresh(playerId: PlayerId) {
        refresh(playerId, sessions.all().count { it.state != SessionState.Disconnected })
    }

    private fun refresh(playerId: PlayerId, onlineCount: Int) {
        val match = matches.findMatchOf(playerId)
        if (match != null && match.phase != MatchPhase.ENDED) {
            lastSent.remove(playerId)
            return
        }
        val layout = layoutFor(playerId, onlineCount)
        if (lastSent[playerId] == layout) return
        lastSent[playerId] = layout
        when (val outcome = hud.showFor(playerId, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> {
                lastSent.remove(playerId)
                log.debug("Lobby sidebar show failed for {}: {}", playerId, outcome.error)
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
                add(text("astera.hud.lobby.queue.mode", "mode" to modeLabel(playerId, entry.mode)))
                add(
                    text(
                        "astera.hud.lobby.queue.position",
                        "position" to positionOf(entry).toString(),
                        "size" to matchmaker.pendingByMode(entry.mode).size.toString(),
                    ),
                )
                add(text("astera.hud.lobby.queue.eta", "eta" to entry.estimatedWaitSec.toString()))
            }
            stars(playerId)?.let {
                add(text("astera.hud.sidebar.spacer"))
                add(text("astera.hud.sidebar.stars", "stars" to it.toString()))
            }
        }
        return HudLayout.Sidebar(titleKey = TITLE_KEY, lines = lines)
    }

    private fun positionOf(entry: QueueEntry): Int {
        val index = matchmaker.pendingByMode(entry.mode).toList().indexOfFirst { it.playerId == entry.playerId }
        return if (index >= 0) index + 1 else 0
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
        private val TITLE_KEY: MessageKey = MessageKey("astera.hud.lobby.title")
    }
}
