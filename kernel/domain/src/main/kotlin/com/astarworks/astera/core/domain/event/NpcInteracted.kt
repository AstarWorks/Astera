package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.NpcInteractionClick
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Player clicked an NPC. The `INpcInteractionRouter` is the primary
 * subscriber (resolves the click to a `GuiIntent`); this event lets
 * other subscribers (analytics, audit log, tutorial progression) observe
 * the same edge without duplicating the routing logic.
 *
 * Wave 5l publishes this from the in-memory NPC adapter test layer; Wave 6b
 * Paper adapter (`PaperNpcInteractionListener`) publishes it from the real
 * `PlayerInteractEntityEvent` listener.
 */
public data class NpcInteracted(
    val handle: EntityHandle,
    val playerId: PlayerId,
    val click: NpcInteractionClick,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
