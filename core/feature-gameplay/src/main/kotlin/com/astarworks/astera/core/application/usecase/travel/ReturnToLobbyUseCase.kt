package com.astarworks.astera.core.application.usecase.travel

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.WorldGatewayError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerLeavingForLobby
import com.astarworks.astera.core.domain.event.PlayerReturnedToLobby
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Teleport a player back to the lobby spawn (Wave 5wireup, ADR-0116 §G15).
 *
 * Wraps [IWorldGateway.lobbySpawn] with a domain event so HUD / analytics
 * subscribers can react. Used by the `/astera travel lobby` command and by
 * the post-match teleport listener
 * ([com.astarworks.astera.match.application.eventbus.listeners.lobby.TeleportToLobbyOnMatchEndedListener]).
 */
public class ReturnToLobbyUseCase(
    private val worldGateway: IWorldGateway,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
) {

    public fun returnToLobby(playerId: PlayerId): Result<Unit, TravelError> {
        eventBus.publish(PlayerLeavingForLobby(playerId))
        return when (val outcome = worldGateway.lobbySpawn(playerId)) {
            is Result.Success -> {
                eventBus.publish(
                    PlayerReturnedToLobby(
                        playerId = playerId,
                        atTick = scheduler.currentTick(),
                    ),
                )
                Result.success(Unit)
            }
            is Result.Failure -> when (outcome.error) {
                is WorldGatewayError.UnknownPlayer,
                is WorldGatewayError.UnknownMatch,
                is WorldGatewayError.Storage,
                -> Result.failure(TravelError.TeleportFailed)
            }
        }
    }
}
