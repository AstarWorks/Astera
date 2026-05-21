package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Fired when a player's spawn-protection window expires (Wave 5o, ADR-0081).
 *
 * Pair of [PlayerProtectionGranted]. Either fires naturally from the
 * scheduled-expiry task inside `InMemorySpawnProtectionGateway`, or
 * explicitly from `revokeProtection` (e.g. PvP-enable transition, admin
 * override).
 */
public data class PlayerProtectionExpired(
    public val playerId: PlayerId,
    public val atTick: Long,
    public override val version: Int = 1,
    public override val occurredAt: Instant = Instant.now(),
) : DomainEvent
