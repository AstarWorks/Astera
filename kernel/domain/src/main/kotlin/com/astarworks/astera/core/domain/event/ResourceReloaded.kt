package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published when a player reloads an Ammo-type weapon via
 * [com.astarworks.astera.core.application.port.outbound.IResourcePoolService.reload].
 *
 * Wave 5k (ADR-0080).
 */
public data class ResourceReloaded(
    val playerId: PlayerId,
    val weapon: ItemId,
    val ammoAfter: Int,
    val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
