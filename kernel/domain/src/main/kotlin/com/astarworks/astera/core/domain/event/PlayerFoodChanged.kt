package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published when [com.astarworks.astera.core.application.port.outbound.IPlayerFoodGateway]
 * mutates a player's food bar, which is the energy bar since F2-2 (ADR-0194).
 *
 * Wave 5k (ADR-0080).
 */
public data class PlayerFoodChanged(
    val playerId: PlayerId,
    val foodLevel: Int,
    val saturation: Float,
    val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
