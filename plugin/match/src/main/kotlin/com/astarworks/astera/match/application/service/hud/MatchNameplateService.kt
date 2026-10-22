package com.astarworks.astera.match.application.service.hud

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerLabelGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.hud.PlayerLabel
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import kotlin.math.ceil

/**
 * Who is on whose side, readable at a glance (ADR-0200): every participant of a live
 * match sees every other participant with the team colour on the nameplate and in the
 * tab list (grouped by team) and the exact HP under the nameplate.
 *
 * The only owner of [IPlayerLabelGateway] for matches. Event triggers (match start /
 * end, health changes, leaving the match) plus a 1 Hz poll that also repairs a
 * viewer whose scoreboard was swapped; the gateway ignores unchanged input, so the
 * poll costs a map comparison per viewer.
 */
public class MatchNameplateService(
    private val matches: IMatchRepository,
    private val labels: IPlayerLabelGateway,
    private val messages: IMessageRenderer,
    private val hpOf: (PlayerId) -> Double?,
    scheduler: IScheduler,
    pollPeriodTicks: Long = DEFAULT_POLL_PERIOD_TICKS,
) {

    private val taskHandle: IScheduler.TaskHandle = scheduler.runRepeatingTicks(
        initialDelayTicks = pollPeriodTicks,
        periodTicks = pollPeriodTicks,
    ) {
        for (match in matches.findActiveMatches()) refresh(match)
    }

    public fun stop() {
        taskHandle.cancel()
    }

    public fun refresh(matchId: MatchId) {
        matches.findById(matchId)?.let { refresh(it) }
    }

    /** A player's HP (or membership) changed: restyle the match they are in. */
    public fun refreshMatchOf(playerId: PlayerId) {
        matches.findMatchOf(playerId)?.let { refresh(it) }
    }

    /** Match over: everyone sees plain names again. */
    public fun release(matchId: MatchId) {
        val match = matches.findById(matchId) ?: return
        for (team in match.teams) for (member in team.members) labels.clear(member)
    }

    /** A player left the match (lobby, quit): they see plain names; the rest drop them. */
    public fun onLeft(playerId: PlayerId) {
        labels.clear(playerId)
        refreshMatchOf(playerId)
    }

    private fun refresh(match: Match) {
        if (match.phase == MatchPhase.ENDED) return
        val styled = buildMap {
            for (team in match.teams) {
                val color = TeamColors.nameOf(team, null, messages)
                for (member in team.members) {
                    put(member, PlayerLabel(group = team.id.value, colorName = color, hp = hpOf(member)?.let(::wholeHp)))
                }
            }
        }
        for (viewer in styled.keys) labels.apply(viewer, styled)
    }

    /** A living player never reads 0 (rounded up), mirroring the status line. */
    private fun wholeHp(hp: Double): Int = ceil(hp.coerceAtLeast(0.0)).toInt()

    public companion object {
        public const val DEFAULT_POLL_PERIOD_TICKS: Long = 20L
    }
}
