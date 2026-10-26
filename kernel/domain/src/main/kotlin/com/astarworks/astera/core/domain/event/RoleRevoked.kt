package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Wave 5t (ADR-0086) — a role was revoked from a player.
 * `revokedBy = null` indicates a system revoke; non-null is admin / GM.
 */
public data class RoleRevoked(
    val playerId: PlayerId,
    val roleId: RoleId,
    val revokedBy: PlayerId?,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
