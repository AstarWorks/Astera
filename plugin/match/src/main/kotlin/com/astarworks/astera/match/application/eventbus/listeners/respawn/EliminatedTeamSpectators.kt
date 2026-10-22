package com.astarworks.astera.match.application.eventbus.listeners.respawn

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.TeamEliminated
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.match.application.usecase.spectator.EnterSpectatorUseCase
import com.astarworks.astera.match.application.usecase.spectator.SpectatorError
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Teams that lost the match (Annihilation: Tier 3 pillar destroyed) stop
 * playing: their members become spectators until the match ends, and a later
 * death never respawns them ([takeOver] is consulted by
 * [RespawnOnPlayerKilledListener] before the respawn).
 */
public class EliminatedTeamSpectators(
    private val matches: IMatchRepository,
    private val healthGateway: IPlayerHealthGateway,
    private val worldGateway: IWorldGateway,
    private val enterSpectator: EnterSpectatorUseCase? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val eliminated: MutableMap<MatchId, MutableSet<TeamId>> = ConcurrentHashMap()

    public fun onTeamEliminated(event: TeamEliminated) {
        eliminated.computeIfAbsent(event.matchId) { ConcurrentHashMap.newKeySet() }.add(event.teamId)
        val match = matches.findById(event.matchId) ?: return
        match.teams.firstOrNull { it.id == event.teamId }?.members?.forEach { toSpectator(match.id, it) }
    }

    public fun onMatchEnded(event: MatchEnded) {
        eliminated.remove(event.matchId)
    }

    /** True (and the player is moved to spectating) when [victim]'s team is already eliminated from [match]. */
    public fun takeOver(match: Match, victim: PlayerId): Boolean {
        val set = eliminated[match.id] ?: return false
        val team = match.teams.firstOrNull { victim in it.members } ?: return false
        if (team.id !in set) return false
        toSpectator(match.id, victim)
        return true
    }

    private fun toSpectator(matchId: MatchId, player: PlayerId) {
        val max = healthGateway.getMaxHealth(player) ?: DEFAULT_MAX_HP
        healthGateway.setHealth(player, max, HealthChangeSource.Respawn)
        val entered = enterSpectator?.enter(player, matchId)
        val needsFallback = entered == null ||
            (entered is Result.Failure && entered.error !is SpectatorError.AlreadySpectator)
        if (needsFallback) {
            val tp = worldGateway.spectatorSpawn(matchId, player)
            if (tp is Result.Failure) log.warn("Eliminated {} in {}: spectator spawn failed: {}", player, matchId, tp.error)
        }
    }

    private companion object {
        const val DEFAULT_MAX_HP = 20.0
    }
}
