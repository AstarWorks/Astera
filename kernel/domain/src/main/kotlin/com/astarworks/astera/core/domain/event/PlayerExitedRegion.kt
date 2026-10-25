package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.region.RegionId
import java.time.Instant

/**
 * Domain-level "the player crossed out of [regionId]" event
 * (Wave 5block-embed, ADR-0120).
 *
 * Pair of [PlayerEnteredRegion]; see that event's KDoc for the producer
 * contract.
 */
public data class PlayerExitedRegion(
    public val playerId: PlayerId,
    public val regionId: RegionId,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
