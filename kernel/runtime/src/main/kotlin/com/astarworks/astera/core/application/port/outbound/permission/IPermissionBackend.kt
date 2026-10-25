package com.astarworks.astera.core.application.port.outbound.permission

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Duration

/**
 * Wave 5t (ADR-0086) — pluggable permission backend.
 *
 * Two impls land in this wave:
 *
 *   1. **MinimalPermissionBackend** — Core default. `content/permissions.yaml`
 *      role tree + Postgres `permission_role_assignment` table (V22).
 *      `backendId = "minimal"`. Always [isAvailable].
 *
 *   2. **LuckPermsPermissionBackend** — skeleton in Wave 5t (`isAvailable`
 *      returns `false` until the LuckPerms plugin is installed); the
 *      LuckPermsProvider wrap lands in Wave 6b. `backendId = "luckperms"`.
 *
 * Same shape as the NPC two-tier (Citizens / Minimal) and the Notification
 * two-tier (Discord / log-only) — pluggable adapter pattern per
 * `.claude/rules/coding-posture.md` §"Application APIs".
 *
 * The composing
 * [com.astarworks.astera.core.application.port.outbound.IPermissionService]
 * implementation owns the selection rule: pick the highest-priority
 * available backend; fall through on unavailability.
 *
 * Konsist enforces:
 *
 *   - `backendId()` is **unique** across all impls in the codebase
 *   - `core.domain.permission.*` does not import `net.luckperms.api.*`
 *     (vendor isolation)
 */
public interface IPermissionBackend {

    /** Stable, unique-across-impls backend id. */
    public fun backendId(): String

    /**
     * Is the backend ready to serve queries? `false` for the LuckPerms
     * backend until the LuckPerms plugin is installed; always `true` for
     * the Minimal backend.
     */
    public fun isAvailable(): Boolean

    public fun check(playerId: PlayerId, node: String): Boolean

    public fun rolesOf(playerId: PlayerId): Set<RoleId>

    public fun grant(
        playerId: PlayerId,
        roleId: RoleId,
        duration: Duration? = null,
        grantedBy: PlayerId? = null,
    ): Result<Unit, RoleError>

    public fun revoke(
        playerId: PlayerId,
        roleId: RoleId,
        revokedBy: PlayerId? = null,
    ): Result<Unit, RoleError>
}
