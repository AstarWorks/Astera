package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Published by
 * [com.astarworks.astera.core.application.eventbus.listeners.item.AutoPickupOnPlayerMovedListener]
 * after a successful walk-over pickup (Wave 5complete, ADR-0117 §Axis 6).
 *
 * Distinct from `EntityPickedUpItem` (`plugin:match`, Wave 5c) which is
 * mode-specific and lives in the Match plugin. This Core variant covers
 * generic loot pickup (PvE drops, world spawn) and is the event Core's
 * audit / achievement listeners subscribe to.
 *
 * @property playerId Player who picked up the loot.
 * @property itemId Item that entered the inventory.
 * @property count Number of items that successfully entered the inventory.
 * @property world World the pickup happened in.
 * @property position Position of the entity at pickup time.
 * @property atTick Server tick at pickup time.
 */
public data class LootPickedUp(
    public val playerId: PlayerId,
    public val itemId: ItemId,
    public val count: Int,
    public val world: WorldId,
    public val position: Vec3,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)

    init {
        require(count > 0) { "LootPickedUp count must be > 0; got $count" }
    }
}
