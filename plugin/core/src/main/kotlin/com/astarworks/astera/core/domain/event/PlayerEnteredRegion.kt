package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.region.RegionId
import java.time.Instant

/**
 * Domain-level "the player crossed into [regionId]" event
 * (Wave 5block-embed, ADR-0120).
 *
 * Derived by [com.astarworks.astera.core.application.eventbus.listeners.region.RegionTransitionDetectorListener]
 * from [PlayerMoved] streams: compares the membership set at `from`
 * vs `to` via [com.astarworks.astera.core.application.port.outbound.IStageRegionIndex],
 * and publishes one [PlayerEnteredRegion] per region newly entered (and
 * one [PlayerExitedRegion] per region left).
 *
 * Subscribers: HUD overlay region badges, region-scoped buff applicators,
 * announcement-on-entry use cases, anti-grief audit log.
 */
public data class PlayerEnteredRegion(
    public val playerId: PlayerId,
    public val regionId: RegionId,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
