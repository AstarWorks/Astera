package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.event.EventRoute
import com.astarworks.astera.core.domain.game.InstanceId

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Domain-level "player picked up an item" placeholder event (Wave 5c).
 *
 * Subscribed by the match sidebar to redraw on pickups (MatchHudWiring).
 * Real producer: `PaperItemPickupAdapter` (Bukkit `EntityPickupItemEvent`).
 *
 * `itemId` is the Astera namespaced item id (e.g. `astera:tnt_carry`).
 * Domain layer never sees Bukkit `ItemStack`.
 */
public data class EntityPickedUpItem(
    val matchId: MatchId,
    val playerId: PlayerId,
    val itemId: String,
    val quantity: Int,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Instance(InstanceId(matchId.value))
}
