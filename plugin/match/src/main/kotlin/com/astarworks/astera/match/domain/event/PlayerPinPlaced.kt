package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.event.EventRoute

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.match.domain.pin.PlayerPin
import java.time.Instant

/**
 * Published when a player places a [PlayerPin] (Wave 5e).
 *
 * Wave 6a's Paper-side renderer subscribes to spawn the DisplayEntity /
 * boss-bar marker. The match plugin owns this event because [PlayerPin]
 * carries a [com.astarworks.astera.core.domain.match.MatchId] —
 * pins are match-scoped.
 */
public data class PlayerPinPlaced(
    val pin: PlayerPin,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global
}
