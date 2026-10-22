package com.astarworks.astera.match.application.service.hud

import com.astarworks.astera.core.adapter.api.hud.ITabList
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.gamemode.IGameMode
import java.util.concurrent.ConcurrentHashMap

/**
 * Header and footer of the tab list (ADR-0206), the single owner of both.
 *
 *  - lobby: the brand, "Lobby", and how many players are online;
 *  - match: the brand, the mode's title, and the viewer's own team plus the number
 *    of players in the match.
 *
 * Rendered per viewer in their locale by [ITabList]; only changes are sent. Players
 * are tracked on join / quit, and a 1 Hz poll covers match membership changing
 * (match start / end, returning to the lobby).
 */
public class TabHeaderFooterService(
    private val matches: IMatchRepository,
    private val tabList: ITabList,
    private val messages: IMessageRenderer,
    private val modeOf: (MatchId) -> IGameMode?,
    scheduler: IScheduler,
    pollPeriodTicks: Long = DEFAULT_POLL_PERIOD_TICKS,
) {

    private data class Content(val header: MessageKey, val footer: MessageKey, val placeholders: Map<String, String>)

    private val online: MutableSet<PlayerId> = ConcurrentHashMap.newKeySet()
    private val lastSent: MutableMap<PlayerId, Content> = ConcurrentHashMap()

    private val taskHandle: IScheduler.TaskHandle = scheduler.runRepeatingTicks(
        initialDelayTicks = pollPeriodTicks,
        periodTicks = pollPeriodTicks,
    ) {
        refreshAll()
    }

    public fun stop() {
        taskHandle.cancel()
    }

    public fun onJoined(playerId: PlayerId) {
        online += playerId
        refreshAll()
    }

    public fun onQuit(playerId: PlayerId) {
        online -= playerId
        lastSent.remove(playerId)
        refreshAll()
    }

    public fun refreshAll() {
        val now = online.toList()
        lastSent.keys.retainAll(now.toSet())
        for (player in now) refresh(player, now.size)
    }

    private fun refresh(player: PlayerId, onlineCount: Int) {
        val match = matches.findMatchOf(player)?.takeIf { it.phase != MatchPhase.ENDED }
        val content = if (match == null) lobbyContent(onlineCount) else matchContent(player, match)
        if (lastSent[player] == content) return
        lastSent[player] = content
        tabList.setHeader(player, content.header, content.placeholders)
        tabList.setFooter(player, content.footer, content.placeholders)
    }

    private fun lobbyContent(onlineCount: Int): Content =
        Content(LOBBY_HEADER, LOBBY_FOOTER, mapOf("count" to onlineCount.toString()))

    private fun matchContent(player: PlayerId, match: Match): Content {
        val team = match.teams.firstOrNull { player in it.members }
        val mode = modeOf(match.id)?.titleKey()?.let { messages.render(player, it) }.orEmpty()
        val placeholders = mapOf(
            "mode" to mode,
            "team" to (team?.let { messages.render(player, it.displayNameKey) } ?: "-"),
            "color" to TeamColors.nameOf(team, player, messages),
            "count" to match.teams.sumOf { it.members.size }.toString(),
        )
        return Content(MATCH_HEADER, MATCH_FOOTER, placeholders)
    }

    public companion object {
        public const val DEFAULT_POLL_PERIOD_TICKS: Long = 20L
        private val LOBBY_HEADER = MessageKey("astera.hud.tab.lobby.header")
        private val LOBBY_FOOTER = MessageKey("astera.hud.tab.lobby.footer")
        private val MATCH_HEADER = MessageKey("astera.hud.tab.match.header")
        private val MATCH_FOOTER = MessageKey("astera.hud.tab.match.footer")
    }
}
