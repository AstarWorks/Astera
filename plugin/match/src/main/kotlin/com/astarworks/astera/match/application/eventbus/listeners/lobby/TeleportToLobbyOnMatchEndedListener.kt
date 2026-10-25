package com.astarworks.astera.match.application.eventbus.listeners.lobby

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.PlayerReturnedToLobby
import org.slf4j.LoggerFactory

/**
 * On [MatchEnded], teleport every participant back to the lobby spawn
 * (Wave 5wireup, ADR-0116 §G5).
 *
 * # Why a listener (not a use case)
 *
 * The post-match teleport is a side effect that **always** follows match
 * end, with no other policy gating it (kit refund, reward distribution
 * etc. are owned by separate listeners on the same event). Splitting it
 * into a dedicated listener keeps each side effect testable in isolation
 * and lets operators disable the teleport by simply skipping its
 * subscription wire in `AsteraMatchPlugin.onEnable`.
 *
 * # Match cleanup
 *
 * This listener does **not** unload the match world — that is the Wave 5d
 * `MatchWorld` lifecycle's job, on a separate `MatchEnded` subscription
 * with its own cleanup timing. The teleport here only moves the players;
 * the world tear-down happens after every player has left.
 *
 * # Failure
 *
 * `IWorldGateway.lobbySpawn` failures are logged at WARN and swallowed.
 * The match-end cleanup chain must continue even if one player's teleport
 * fails (offline, in transition between worlds, etc.); operators triage
 * via the audit log.
 *
 * # PlayerReturnedToLobby
 *
 * For each successfully-teleported participant, the listener publishes
 * [PlayerReturnedToLobby] so HUD / analytics subscribers see the lobby
 * re-entry signal regardless of which path (this listener vs. an explicit
 * `/astera travel lobby`) caused it.
 */
public class TeleportToLobbyOnMatchEndedListener(
    private val matches: IMatchRepository,
    private val worldGateway: IWorldGateway,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: MatchEnded) {
        val match = matches.findById(event.matchId)
        if (match == null) {
            log.warn(
                "MatchEnded for {} but the match is no longer in the repository; skipping lobby teleport.",
                event.matchId,
            )
            return
        }

        val participants = match.teams.flatMap { it.members }
        if (participants.isEmpty()) {
            log.debug(
                "MatchEnded for {}: no participants to teleport.",
                event.matchId,
            )
            return
        }

        for (playerId in participants) {
            scheduler.run(ExecutionScope.Entity(playerId.uuid), kotlin.time.Duration.ZERO) {
                worldGateway.lobbySpawn(playerId).thenAccept { outcome ->
                    when (outcome) {
                        is Result.Success -> eventBus.publish(PlayerReturnedToLobby(playerId, scheduler.currentTick()))
                        is Result.Failure -> log.warn("Post-match lobby teleport for {} failed: {}", playerId, outcome.error::class.simpleName)
                    }
                }
            }
        }
    }
}
