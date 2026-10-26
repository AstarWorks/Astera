package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.portal.PortalId
import java.time.Instant

/**
 * Domain-level "a portal fired and teleported a player" event
 * (Wave 5block-embed, ADR-0120).
 *
 * Published by the portal activation listeners after a successful
 * `IWorldGateway.teleport` call. Distinct from [PlayerTeleported]
 * (which is the *raw* teleport surface) because portal activation
 * carries the [portalId] semantic that downstream subscribers
 * (telemetry, achievements, replay narration) want to consume
 * without re-deriving from a position match.
 *
 * Adapters that record the lower-level teleport will also publish a
 * [PlayerTeleported]; subscribers that only care about portals should
 * subscribe to [PortalActivated] for the higher-fidelity signal.
 */
public data class PortalActivated(
    public val playerId: PlayerId,
    public val portalId: PortalId,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
