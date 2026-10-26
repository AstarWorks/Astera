package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Player successfully teleported into the PvE open-world (Wave 5wireup,
 * ADR-0116 §G13).
 *
 * Emitted by `TravelToPveWorldUseCase` after `IWorldGateway.teleport`
 * returns true. Future PvE-side listeners (mob spawn boost, PvE music cue,
 * tutorial gate "first PvE visit") subscribe to this rather than the
 * generic `PlayerTeleported`, which fires for every teleport.
 */
public data class PlayerTraveledToPveWorld(
    val playerId: PlayerId,
    val worldId: WorldId,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
