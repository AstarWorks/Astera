package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.SpeedAxis
import java.time.Instant

/**
 * Published when [com.astarworks.astera.core.application.port.outbound.IPlayerSpeedGateway.setMultiplier]
 * or [com.astarworks.astera.core.application.port.outbound.IPlayerSpeedGateway.resetMultiplier]
 * mutates a player's speed axis.
 *
 * Wave 5k (ADR-0080).
 */
public data class PlayerSpeedChanged(
    val playerId: PlayerId,
    val axis: SpeedAxis,
    val oldMultiplier: Double?,
    val newMultiplier: Double,
    val ttlTicks: Int,
    val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent
