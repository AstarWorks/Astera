package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published when sprint-state is forced via
 * [com.astarworks.astera.core.application.port.outbound.IPlayerSpeedGateway.setSprinting].
 *
 * Wave 5k (ADR-0080).
 */
public data class PlayerSprintingChanged(
    val playerId: PlayerId,
    val sprinting: Boolean,
    val ttlTicks: Int,
    val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent
