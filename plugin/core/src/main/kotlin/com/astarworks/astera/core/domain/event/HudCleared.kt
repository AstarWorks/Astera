package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.hud.HudHandle
import com.astarworks.astera.core.domain.hud.HudRegion
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * One or more HUD handles for a player were cleared from a region
 * (Wave 5j, ADR-0097).
 *
 * Published by `IHudGateway.clearFor` implementations on dispatch
 * success. The call is **idempotent** per port contract: a clear on a
 * region with no active HUDs still publishes this event with an empty
 * [clearedHandles] list — that gives audit subscribers a complete
 * record of every clear attempt (Phase 4 admin-tool / match-cleanup
 * observability needs the no-op events to track caller intent).
 *
 * Also fired by ActionBar TTL auto-expiry (Wave 5j InMemoryHudGateway
 * scheduler hook) — distinguishable only by the absence of a preceding
 * explicit caller, which subscribers reconstruct from their own
 * bookkeeping.
 */
public data class HudCleared(
    val playerId: PlayerId,
    val region: HudRegion,
    val clearedHandles: List<HudHandle> = emptyList(),
    val atTick: Long = 0L,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
