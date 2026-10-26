package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.ResourceType
import java.time.Instant

/**
 * Published when a player consumes [amount] of [type] via
 * [com.astarworks.astera.core.application.port.outbound.IResourcePoolService.spendResource].
 *
 * Wave 5k (ADR-0080).
 */
public data class ResourceSpent(
    val playerId: PlayerId,
    val type: ResourceType,
    val amount: Int,
    val remaining: Int,
    val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
