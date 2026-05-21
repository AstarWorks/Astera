package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published when a player's max-HP attribute is mutated via
 * [com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway.setMaxHealth].
 *
 * Wave 5k (ADR-0080).
 */
public data class PlayerMaxHealthChanged(
    val playerId: PlayerId,
    val oldMaxHp: Double?,
    val newMaxHp: Double,
    val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent
