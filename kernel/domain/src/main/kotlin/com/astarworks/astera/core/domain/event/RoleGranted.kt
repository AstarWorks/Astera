package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Wave 5t (ADR-0086) — a role was granted to a player.
 *
 * `grantedBy = null` indicates a system grant (e.g., first-time-login default
 * role); a non-null value is the admin / GM who issued the grant.
 * `expiresAt = null` means permanent.
 */
public data class RoleGranted(
    val playerId: PlayerId,
    val roleId: RoleId,
    val grantedBy: PlayerId?,
    val expiresAt: Instant?,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
