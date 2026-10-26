package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Wave 5t (ADR-0086) — a time-bounded role grant expired naturally.
 *
 * Emitted by the Minimal backend's expiry sweep + by the LuckPerms backend's
 * expiry callback (Wave 6b). Subscribers: audit log + Discord webhook
 * notifier (Phase 4).
 */
public data class RoleExpired(
    val playerId: PlayerId,
    val roleId: RoleId,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
