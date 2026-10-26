package com.astarworks.astera.match.application.eventbus.listeners.respawn

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.spectator.ISpectator
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.TeamEliminated
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.match.application.service.death.SpectatorTargetCycler
import com.astarworks.astera.match.application.usecase.spectator.EnterSpectatorUseCase
import com.astarworks.astera.match.application.usecase.spectator.LeaveSpectatorUseCase
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Teams that lost the match (Annihilation: Tier 3 pillar destroyed) stop
 * playing: their members become real spectators (Bukkit spectator mode, camera
 * on a living player, left/right click to cycle) until the match ends, and a
 * later death never respawns them ([takeOver] is consulted by
 * [DeathRespawnCoordinator] before the respawn countdown).
 *
 * Spectators stay where they are in the arena; nothing here teleports them to
 * the lobby. [onMatchEnded] returns them to normal gameplay mode so the
 * lobby teleport that follows does not leave them in spectator mode.
 */
public class EliminatedTeamSpectators(
    private val matches: IMatchRepository,
    private val healthGateway: IPlayerHealthGateway,
    private val spectator: ISpectator? = null,
    private val enterSpectator: EnterSpectatorUseCase? = null,
    private val leaveSpectator: LeaveSpectatorUseCase? = null,
    private val cycler: SpectatorTargetCycler? = null,
    private val hud: IHudGateway? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val eliminated: MutableMap<MatchId, MutableSet<TeamId>> = ConcurrentHashMap()
    private val spectators: MutableMap<MatchId, MutableSet<PlayerId>> = ConcurrentHashMap()

    public fun onTeamEliminated(event: TeamEliminated) {
        eliminated.computeIfAbsent(event.matchId) { ConcurrentHashMap.newKeySet() }.add(event.teamId)
        val match = matches.findById(event.matchId) ?: return
        val members = match.teams.firstOrNull { it.id == event.teamId }?.members.orEmpty()
        // Everyone switches first so no one's camera lands on a teammate who is about to spectate too.
        members.forEach { enter(match.id, it) }
        members.forEach { focus(it) }
    }

    public fun onMatchEnded(event: MatchEnded) {
        eliminated.remove(event.matchId)
        spectators.remove(event.matchId)?.forEach { player ->
            spectator?.stopSpectating(player)
            leaveSpectator?.leave(player)
            cycler?.clear(player)
        }
    }

    /** True when [player]'s team is already eliminated from [match]. */
    public fun isEliminated(match: Match, player: PlayerId): Boolean {
        val set = eliminated[match.id] ?: return false
        val team = match.teams.firstOrNull { player in it.members } ?: return false
        return team.id in set
    }

    /** True (and the player is moved to spectating) when [victim]'s team is already eliminated from [match]. */
    public fun takeOver(match: Match, victim: PlayerId): Boolean {
        if (!isEliminated(match, victim)) return false
        enter(match.id, victim)
        focus(victim)
        return true
    }

    private fun enter(matchId: MatchId, player: PlayerId) {
        // A defeated player keeps a full HP bar while spectating.
        val max = healthGateway.getMaxHealth(player) ?: DEFAULT_MAX_HP
        healthGateway.setHealth(player, max, HealthChangeSource.Respawn)

        // enter() would teleport via IWorldGateway.spectatorSpawn (lobby stub); the wiring
        // gives it no world gateway so the spectator stays in the arena.
        enterSpectator?.enter(player, matchId)
        if (spectator != null) {
            when (val started = spectator.startSpectating(player)) {
                is Result.Success -> Unit
                is Result.Failure -> log.warn("Eliminated {} in {}: spectator mode failed: {}", player, matchId, started.error)
            }
        }
        spectators.computeIfAbsent(matchId) { ConcurrentHashMap.newKeySet() }.add(player)
        hud?.showFor(
            player,
            HudLayout.Subtitle(
                component = HudComponent.Text(SUBTITLE, HudColor.GRAY),
                title = HudComponent.Text(TITLE, HudColor.RED),
                fadeInTicks = 5,
                stayTicks = 60,
                fadeOutTicks = 15,
            ),
        )
    }

    private fun focus(player: PlayerId) {
        cycler?.focusFirst(player)
    }

    private companion object {
        const val DEFAULT_MAX_HP = com.astarworks.astera.core.domain.attribute.Attributes.DEFAULT_PLAYER_MAX_HP
        val TITLE = MessageKey("astera.death.eliminated.title")
        val SUBTITLE = MessageKey("astera.death.eliminated.subtitle")
    }
}
