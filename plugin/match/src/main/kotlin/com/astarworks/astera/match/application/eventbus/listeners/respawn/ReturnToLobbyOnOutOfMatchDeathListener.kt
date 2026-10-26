package com.astarworks.astera.match.application.eventbus.listeners.respawn

import java.util.concurrent.CompletableFuture

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import org.slf4j.LoggerFactory

/**
 * Out-of-match death fallback: complements [RespawnOnPlayerKilledListener],
 * which returns early when the victim is not in a match.
 *
 * Astera HP (ADR-0149) is the source of truth and Bukkit health is floored
 * at 1.0, so vanilla death never fires; without this a lobby / flat-world
 * death leaves the player at 0 Astera HP. On [PlayerKilled] with no active
 * match: clear status effects, send the player to the lobby spawn, restore
 * HP to max, and send a short notice. In-match deaths are ignored here.
 *
 * HP is restored even when the lobby teleport fails (the goal is "player is
 * playable again"); the failure is logged at WARN.
 *
 * **Game-only backends** ([gameServer]): there is no lobby world on this server, `lobbySpawn` would
 * drop the victim at the default world spawn (looks like "dying kicked me out of the arena") and a
 * death can legitimately land outside a registered match: while the other assigned players are still
 * arriving, or in the result window after the match ended. Those players are revived where they
 * stand; the assignment receiver is the only thing that moves players off a game server (proxy
 * transfer after the match / bounce of strays).
 */
public class ReturnToLobbyOnOutOfMatchDeathListener(
    private val matches: IMatchRepository,
    private val healthGateway: IPlayerHealthGateway,
    private val worldGateway: IWorldGateway,
    private val playerGateway: IPlayerGateway,
    private val messages: IMessageRenderer,
    private val statusEffects: IStatusEffectContainer? = null,
    private val gameServer: Boolean = false,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: PlayerKilled) {
        val victim = event.victim
        if (matches.findMatchOf(victim) != null) return

        statusEffects?.clear(victim)

        val placed = if (gameServer) CompletableFuture.completedFuture(Result.success(Unit))
            else worldGateway.lobbySpawn(victim)
        placed.thenAccept { tp ->
            if (tp is Result.Failure) log.warn("Out-of-match death: lobby spawn for {} failed: {}", victim, tp.error)
        val max = healthGateway.getMaxHealth(victim) ?: DEFAULT_MAX_HP
        when (val hp = healthGateway.setHealth(victim, max, HealthChangeSource.Respawn)) {
            is Result.Success -> Unit
            is Result.Failure -> log.warn("Out-of-match death: HP restore for {} failed: {}", victim, hp.error)
        }

        playerGateway.sendMessage(victim, messages.render(victim, if (gameServer) MSG_HERE else MSG, emptyMap()))
        }
    }

    private companion object {
        const val DEFAULT_MAX_HP = com.astarworks.astera.core.domain.attribute.Attributes.DEFAULT_PLAYER_MAX_HP
        val MSG = MessageKey("astera.hud.respawn.out-of-match")
        val MSG_HERE = MessageKey("astera.hud.respawn.out-of-match-here")
    }
}
