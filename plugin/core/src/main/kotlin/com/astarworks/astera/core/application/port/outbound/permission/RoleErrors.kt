package com.astarworks.astera.core.application.port.outbound.permission

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Wave 5t (ADR-0086) — sealed errors for role/permission backend ops.
 *
 * Distinct from the pre-existing `AssignRoleError` / `RevokeRoleError` (Wave 3,
 * `IPermissionService`) because the Wave 5t two-tier surface has slightly
 * different semantics: granting can fail because LuckPerms is offline,
 * because the role is missing from the registry, or because the requested
 * expiry duration is invalid. Coexists with the legacy errors; the two
 * surfaces map cleanly at the wiring layer.
 */
public sealed class RoleError {
    public abstract val messageKey: MessageKey

    public data class RoleNotFound(val roleId: RoleId) : RoleError() {
        override val messageKey: MessageKey = MessageKey("astera.permission.role_not_found")
    }

    public data class AlreadyAssigned(val playerId: PlayerId, val roleId: RoleId) : RoleError() {
        override val messageKey: MessageKey = MessageKey("astera.permission.already_assigned")
    }

    public data class NotAssigned(val playerId: PlayerId, val roleId: RoleId) : RoleError() {
        override val messageKey: MessageKey = MessageKey("astera.permission.not_assigned")
    }

    /** A backend is registered but currently not available (e.g. LuckPerms not installed). */
    public data class BackendUnavailable(val backendId: String) : RoleError() {
        override val messageKey: MessageKey = MessageKey("astera.permission.backend_unavailable")
    }

    /** Underlying backend error (DB, LuckPerms API, …). */
    public data class BackendFailure(val reason: String) : RoleError() {
        override val messageKey: MessageKey = MessageKey("astera.permission.backend_failure")
    }
}

/**
 * Why was a permission denied? Phase 1 records the immediate cause; Phase 4
 * may add chain-of-decision detail (which role provided which rule).
 */
public sealed interface PermissionDenySource {
    public data object NoMatchingRule : PermissionDenySource
    public data class ExplicitDeny(val byRoleId: RoleId) : PermissionDenySource
    public data class PlayerOffline(val playerId: PlayerId) : PermissionDenySource
}
