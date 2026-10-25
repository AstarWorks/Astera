package com.astarworks.astera.core.domain.permission

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Wave 5t (ADR-0086) — domain-level reason a permission check returned false.
 *
 * Lives in `core.domain.permission` so [com.astarworks.astera.core.domain.event.PermissionDenied]
 * can reference it without crossing the domain→application boundary.
 *
 * The application-layer error type [com.astarworks.astera.core.application.port.outbound.permission.PermissionDenySource]
 * maps 1:1 to this — they coexist to keep the layer boundary explicit.
 */
public sealed interface PermissionDenyReason {
    public data object NoMatchingRule : PermissionDenyReason
    public data class ExplicitDeny(val byRoleId: RoleId) : PermissionDenyReason
    public data class PlayerOffline(val playerId: PlayerId) : PermissionDenyReason
}
