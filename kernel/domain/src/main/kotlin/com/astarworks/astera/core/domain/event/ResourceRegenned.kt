package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.ResourceType
import java.time.Instant

/**
 * Published when [com.astarworks.astera.core.application.port.outbound.IResourceRegenScheduler]
 * flushes a whole-integer of [amount] into a player's [type] pool.
 *
 * Wave 5k (ADR-0080).
 */
public data class ResourceRegenned(
    val playerId: PlayerId,
    val type: ResourceType,
    val amount: Int,
    val newValue: Int,
    val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
