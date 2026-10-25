package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Player left a matchmaking queue (Wave 5w / ADR-0089).
 *
 * Fires for explicit `/astera queue leave`, party rebalancing, or a
 * [MatchFormed] consuming the entry. `reasonTag` keeps subscribers
 * (HUD / metrics) plugin-agnostic.
 */
public data class MatchQueueLeft(
    val playerId: PlayerId,
    val modeKey: String,
    val reasonTag: String,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
