package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published when [com.astarworks.astera.core.application.port.outbound.visibility.IPlayerVisibilityGateway]
 * successfully hides [target] from [viewer]. Useful for HUD updates (mini-map
 * removes the dot), spectator notifications, or replay-system masking.
 *
 * Wave 5y (ADR-0090).
 */
public data class EntityHiddenFromViewer(
    val viewer: PlayerId,
    val target: EntityHandle,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent
