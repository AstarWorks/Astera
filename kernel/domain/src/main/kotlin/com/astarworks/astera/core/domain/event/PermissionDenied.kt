package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.permission.PermissionDenyReason
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Wave 5t (ADR-0086) — a permission check returned `false` for [node].
 *
 * Useful for audit-log streaming and for surfacing "why was I denied?" to
 * admin tooling. Phase 1 PoC records the immediate cause; Phase 4 may add
 * chain-of-decision detail.
 */
public data class PermissionDenied(
    val playerId: PlayerId,
    val node: String,
    val source: PermissionDenyReason,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
