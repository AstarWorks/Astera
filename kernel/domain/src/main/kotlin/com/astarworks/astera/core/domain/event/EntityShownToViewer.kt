package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Inverse of [EntityHiddenFromViewer] — published when an entity becomes
 * visible to a viewer again (either explicit `showEntity` / `showPlayer` or
 * implicit on viewer logout-with-cleanup).
 *
 * Wave 5y (ADR-0090).
 */
public data class EntityShownToViewer(
    val viewer: PlayerId,
    val target: EntityHandle,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(viewer.uuid)
}
