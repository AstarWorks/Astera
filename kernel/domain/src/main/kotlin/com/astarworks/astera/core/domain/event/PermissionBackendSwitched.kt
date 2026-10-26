package com.astarworks.astera.core.domain.event

import java.time.Instant

/**
 * Wave 5t (ADR-0086) — the active permission backend changed at runtime.
 *
 * Fired when LuckPerms becomes available (or unavailable) and the selector
 * flips between the Minimal and LuckPerms backends. Subscribers: audit log,
 * Discord operator alert (Phase 4), Web admin dashboard refresh.
 */
public data class PermissionBackendSwitched(
    val oldBackend: String,
    val newBackend: String,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global
}
