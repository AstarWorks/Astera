package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.anticheat.AnticheatAction
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published when the anti-cheat orchestrator decides on (and executes) an
 * [AnticheatAction] against a player.
 *
 * Wave 5q (ADR-0083).
 */
public data class AnticheatActionTaken(
    val playerId: PlayerId,
    val action: AnticheatAction,
    val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent
