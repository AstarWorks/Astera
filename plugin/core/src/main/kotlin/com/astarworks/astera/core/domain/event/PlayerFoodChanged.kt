package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published when [com.astarworks.astera.core.application.port.outbound.IPlayerFoodGateway]
 * mutates a player's hunger bar state.
 *
 * `suppressed = true` means the bar is locked full (custom-HUD takes over);
 * `suppressed = false` means the values were written directly.
 *
 * Wave 5k (ADR-0080).
 */
public data class PlayerFoodChanged(
    val playerId: PlayerId,
    val foodLevel: Int,
    val saturation: Float,
    val suppressed: Boolean,
    val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent
