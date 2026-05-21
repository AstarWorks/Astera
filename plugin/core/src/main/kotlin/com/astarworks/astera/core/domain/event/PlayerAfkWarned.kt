package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Player crossed the AFK warning threshold (default 30s of inactivity).
 * HUD subscribers show a countdown; nothing irreversible happens yet.
 *
 * Wave 5q (ADR-0083).
 */
public data class PlayerAfkWarned(
    val playerId: PlayerId,
    val secondsRemaining: Int,
    val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent
