package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.pvp.PvpDisableReason
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Fired when an incoming damage event was rejected by
 * [com.astarworks.astera.core.application.port.outbound.IPlayerPvpStateService.resolveCanDamage]
 * (Wave 5o, ADR-0081).
 *
 * Audit / analytics listeners use this to track PvP-toggle effectiveness:
 * how often spawn protection deflected an attack, how often a safe-zone
 * region rule fired, etc. The combat pipeline does NOT consume this event
 * — it has already taken the "no damage applied" branch by the time the
 * event publishes.
 */
public data class PvpDamageBlocked(
    public val attackerId: PlayerId,
    public val victimId: PlayerId,
    public val reason: PvpDisableReason,
    public val atTick: Long,
    public override val version: Int = 1,
    public override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(attackerId.uuid)
}
