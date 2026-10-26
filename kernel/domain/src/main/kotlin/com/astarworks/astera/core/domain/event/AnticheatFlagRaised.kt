package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.anticheat.AnticheatSuspicion
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published whenever an [AnticheatSuspicion] crosses the local validator
 * (or arrives from an external bridge). Subscribers include admin
 * notification, audit log, and any downstream automation.
 *
 * Wave 5q (ADR-0083).
 */
public data class AnticheatFlagRaised(
    val playerId: PlayerId,
    val suspicion: AnticheatSuspicion,
    val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
