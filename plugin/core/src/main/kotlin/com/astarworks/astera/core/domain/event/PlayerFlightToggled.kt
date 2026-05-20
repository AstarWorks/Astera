package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published when allow-flight is toggled via
 * [com.astarworks.astera.core.application.port.outbound.IPlayerSpeedGateway.setAllowFlight].
 *
 * Wave 5k (ADR-0080).
 */
public data class PlayerFlightToggled(
    val playerId: PlayerId,
    val allowed: Boolean,
    val ttlTicks: Int,
    val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent
