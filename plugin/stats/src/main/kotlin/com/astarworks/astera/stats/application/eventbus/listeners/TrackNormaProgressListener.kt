package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.core.domain.norma.NormaTarget
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.INormaService
import com.astarworks.astera.stats.domain.event.MissionCompleted
import com.astarworks.astera.stats.domain.event.XpGranted
import org.slf4j.LoggerFactory

/**
 * Feeds the non-kill mission targets (ADR-0210; kills stay in [MissionProgressOnKillListener]):
 * `damage` from [DamageDealt], `match_wins` from [MatchEnded] (every member of the winning team)
 * and `xp_gain` from [XpGranted]. Missions are `content/norma/`; every newly completed one is
 * announced as [MissionCompleted].
 *
 * XP granted as a mission reward or by an admin does not count towards `xp_gain`, so completing a
 * mission cannot chain into the next one by itself.
 */
public class TrackNormaProgressListener(
    private val normaService: INormaService,
    private val eventBus: IDomainEventBus,
    private val matchRepository: IMatchRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: DamageDealt) {
        val attacker = event.attacker ?: return
        if (attacker == event.target) return
        val damage = event.resolved.finalAmount.toLong()
        if (damage <= 0L) return
        record(attacker, NormaTarget.Damage(1), damage)
    }

    public fun handle(event: MatchEnded) {
        val winningTeam = event.winningTeam ?: return
        val match = matchRepository.findById(event.matchId) ?: run {
            log.debug("TrackNormaProgressListener: match {} not found", event.matchId)
            return
        }
        val winners = match.teams.firstOrNull { it.id == winningTeam }?.members ?: return
        for (playerId in winners) record(playerId, NormaTarget.MatchWins(1), 1)
    }

    public fun handle(event: XpGranted) {
        if (event.source == XpSource.MissionReward || event.source == XpSource.AdminGrant) return
        record(event.playerId, NormaTarget.XpGain(1), event.amount)
    }

    private fun record(playerId: PlayerId, target: NormaTarget, delta: Long) {
        val completed = runCatching { normaService.recordProgress(playerId, target, delta) }
            .onFailure { log.debug("TrackNormaProgressListener: {} for {} failed: {}", target, playerId, it.message) }
            .getOrNull() ?: return
        if (completed.isEmpty()) return
        val definitions = normaService.activeNormasFor(playerId).associateBy { it.id }
        for (progress in completed) {
            val norma = definitions[progress.normaId] ?: continue
            eventBus.publish(MissionCompleted(playerId, progress.normaId, norma.reward))
        }
    }
}
