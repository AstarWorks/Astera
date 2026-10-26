package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published when a regen rate is set or cleared via
 * [com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway].
 *
 * [hpPerSecond] = 0.0 means regen was cleared. [ttlTicks] = `Int.MAX_VALUE`
 * means indefinite.
 *
 * Wave 5k (ADR-0080).
 */
public data class PlayerRegenRateChanged(
    val playerId: PlayerId,
    val hpPerSecond: Double,
    val ttlTicks: Int,
    val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent
