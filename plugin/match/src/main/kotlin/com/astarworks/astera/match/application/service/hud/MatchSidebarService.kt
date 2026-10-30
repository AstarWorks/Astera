package com.astarworks.astera.match.application.service.hud

import java.util.concurrent.ConcurrentHashMap

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.gamemode.IGameMode
import com.astarworks.astera.match.domain.norma.NormaHud
import org.slf4j.LoggerFactory

/**
 * The match sidebar's single owner (WP14, ADR-0177).
 *
 * Replaces the old `UpdateMatchSidebarListener`, which drew a placeholder
 * "Team {team}: {score}" line without values and was swallowed by the Paper
 * gateway's sidebar stub. For every live match it asks the match's
 * [IGameMode] what to show ([IGameMode.hudStatus]), localises it per viewer
 * and pushes it as the viewer's sidebar.
 *
 * # Lazy updates
 *
 * Event-driven refreshes ([refresh] / [refreshPlayer], called from the wiring
 * on match start, phase change, kills, pickups and wallet changes) plus a
 * [pollPeriodTicks] poll that only exists to advance the clock and catch
 * mode-internal state without its own event (pillar HP, captures). Every push
 * is compared with the last layout sent to that player and dropped when
 * identical, so an idle match costs one comparison per player per second and
 * no packets.
 */
public class MatchSidebarService(
    private val matches: IMatchRepository,
    private val modeOf: (MatchId) -> IGameMode?,
    private val hud: IHudGateway,
    private val messages: IMessageRenderer,
    private val norma: (PlayerId) -> NormaHud?,
    /** Ticks of ACTIVE time the match is limited to (0 = unlimited); shown next to the clock. */
    private val timeLimitTicks: (MatchId) -> Long = { 0L },
    /** Count-model placeholders of the match's mode (`start`, `per_<event>`, `need_<event>`) for the objective one-liner. */
    private val objectiveParams: (MatchId) -> Map<String, String> = { emptyMap() },
    private val scheduler: IScheduler,
    private val killTally: MatchKillTally = MatchKillTally(),
    pollPeriodTicks: Long = DEFAULT_POLL_PERIOD_TICKS,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val failureLogged: MutableSet<PlayerId> = ConcurrentHashMap.newKeySet()
    private val lastSent: MutableMap<PlayerId, HudLayout.Sidebar> = ConcurrentHashMap()

    private val taskHandle: IScheduler.TaskHandle = scheduler.runRepeatingTicks(ExecutionScope.Global,
        initialDelayTicks = pollPeriodTicks,
        periodTicks = pollPeriodTicks,
    ) {
        for (match in matches.findActiveMatches()) refresh(match)
    }

    /** Cancel the poll. Idempotent. */
    public fun stop() {
        taskHandle.cancel()
    }

    public fun refresh(matchId: MatchId) {
        matches.findById(matchId)?.let { refresh(it) }
    }

    /** Re-draw one player's sidebar (wallet change); no-op when the player is not in a live match. */
    public fun refreshPlayer(playerId: PlayerId) {
        val match = matches.findMatchOf(playerId)?.takeIf { it.isLive() } ?: return
        val mode = modeOf(match.id) ?: return
        val model = MatchSidebarModel.build(mode, match, scheduler.currentTick(), timeLimitTicks(match.id), objectiveParams(match.id))
        push(match, model, playerId)
    }

    /**
     * A client that disconnected lost its sidebar: drop what we think it shows so the next push is sent
     * even when the layout is unchanged (a returning player would otherwise see the lobby sidebar).
     */
    public fun forget(playerId: PlayerId) {
        lastSent.remove(playerId)
    }

    /**
     * The match is over: forget what was sent to its members. The sidebar itself
     * stays up (final scores) until the lobby service draws over it; clearing the
     * region here would race with that and leave the player with no sidebar.
     */
    public fun release(matchId: MatchId) {
        killTally.clear(matchId)
        val match = matches.findById(matchId) ?: return
        for (team in match.teams) {
            for (member in team.members) lastSent.remove(member)
        }
    }

    private fun refresh(match: Match) {
        if (!match.isLive()) return
        val mode = modeOf(match.id) ?: return
        val model = MatchSidebarModel.build(mode, match, scheduler.currentTick(), timeLimitTicks(match.id), objectiveParams(match.id))
        for (team in match.teams) {
            for (member in team.members) scheduler.run(ExecutionScope.Entity(member.uuid), kotlin.time.Duration.ZERO) { push(match, model, member) }
        }
    }

    private fun push(match: Match, model: SidebarModel, playerId: PlayerId) {
        // The push may run a tick after the poll; a match that ended meanwhile hands the sidebar to the lobby service.
        if (matches.findById(match.id)?.isLive() != true) return
        val layout = MatchSidebarModel.layoutFor(model, match, playerId, messages, norma(playerId), killTally.of(match.id, playerId))
        if (lastSent[playerId] == layout) return
        lastSent[playerId] = layout
        when (val outcome = hud.showFor(playerId, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> {
                lastSent.remove(playerId)
                if (failureLogged.add(playerId)) log.warn("Match sidebar show failed for {} in {}: {}", playerId, match.id, outcome.error)
            }
        }
    }

    private fun Match.isLive(): Boolean = phase != MatchPhase.ENDED

    public companion object {
        /** One second: the clock line changes once per second, so faster polling would only re-compare. */
        public const val DEFAULT_POLL_PERIOD_TICKS: Long = 20L
    }
}
