package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * A player's E.G.O gauge moved (ADR-0231). [value] is the new value, [max] the
 * cap; [becameFull] is true on the single change that reaches the cap, so the
 * HUD can play the "E.G.O READY" cue once.
 */
public data class EgoGaugeChanged(
    val playerId: PlayerId,
    val value: Int,
    val max: Int,
    val becameFull: Boolean,
    val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
