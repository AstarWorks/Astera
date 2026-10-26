package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * [playerId] asked to go to the lobby (`/astera travel lobby`, the travel menu) and is about to be
 * teleported. Published **before** the teleport so a running match can still read where they stand
 * (stars burst there) and treat the leave like a disconnect.
 */
public data class PlayerLeavingForLobby(
    val playerId: PlayerId,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
