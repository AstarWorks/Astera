package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Player crossed the AFK kick threshold (default 2 min of inactivity).
 * Subscribers (match plumbing, leaver-penalty service) act on this:
 * match removes the player and Wave 5w queue applies a join-cooldown.
 *
 * Wave 5q (ADR-0083).
 */
public data class PlayerAfkKicked(
    val playerId: PlayerId,
    val durationSec: Int,
    val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
