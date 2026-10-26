package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published when a player's active weapon binding flips via
 * [com.astarworks.astera.core.application.port.outbound.IResourcePoolService.setActiveWeapon].
 *
 * The HUD-layer subscriber uses this as the trigger to re-render the
 * resource bar for the new archetype's [com.astarworks.astera.core.domain.resource.ResourceType].
 *
 * Wave 5k (ADR-0080).
 */
public data class PlayerActiveWeaponChanged(
    val playerId: PlayerId,
    val previous: ItemId?,
    val current: ItemId?,
    val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent
