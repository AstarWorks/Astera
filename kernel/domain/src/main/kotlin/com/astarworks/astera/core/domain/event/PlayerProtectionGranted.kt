package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Fired by `InMemorySpawnProtectionGateway.grantProtection` (Wave 5o,
 * ADR-0081) when a player enters a post-respawn invulnerability window.
 *
 * HUD adapters, audit listeners, and the PvP service all consume this; the
 * matching expiry side is [PlayerProtectionExpired].
 */
public data class PlayerProtectionGranted(
    public val playerId: PlayerId,
    public val durationSeconds: Int,
    public val atTick: Long,
    public override val version: Int = 1,
    public override val occurredAt: Instant = Instant.now(),
) : DomainEvent
