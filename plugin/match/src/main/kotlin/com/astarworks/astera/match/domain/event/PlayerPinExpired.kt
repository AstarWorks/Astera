package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.event.EventRoute

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.match.domain.pin.PlayerPinId
import java.time.Instant

/**
 * Published when a [PlayerPin] expires (either by lifetime exhaustion
 * or explicit removal) — Wave 5e.
 *
 * Wave 6a's Paper-side renderer subscribes to despawn the marker.
 */
public data class PlayerPinExpired(
    val pinId: PlayerPinId,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global
}
