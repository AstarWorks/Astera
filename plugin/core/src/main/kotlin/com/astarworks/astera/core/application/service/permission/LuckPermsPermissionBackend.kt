package com.astarworks.astera.core.application.service.permission

import com.astarworks.astera.core.application.port.outbound.permission.IPermissionBackend
import com.astarworks.astera.core.application.port.outbound.permission.RoleError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Duration

/**
 * Wave 5t (ADR-0086) — LuckPerms backend skeleton.
 *
 * Phase 1 PoC ships this as a permanently-unavailable stub: [isAvailable]
 * returns the value supplied at construction (default `false`), and every
 * mutating operation returns `BackendUnavailable`. The real LuckPerms wrap
 * (`net.luckperms.api.LuckPermsProvider.get()`) lands in Wave 6b as a Paper
 * adapter under `core.adapter.paper.permission.PaperLuckPermsBackend` so
 * the domain layer does not import `net.luckperms.api.*` (Konsist vendor
 * isolation rule lands in this wave).
 *
 * Why ship the stub in Wave 5t at all:
 *   - Locks the [IPermissionBackend.backendId] = `"luckperms"` slot
 *   - Lets the [DefaultPermissionService] selector test cover the
 *     fall-through path
 *   - Gives the Konsist `IPermissionBackend implementations must declare
 *     backendId() uniquely` rule something to enforce against
 */
public class LuckPermsPermissionBackend(
    private val available: () -> Boolean = { false },
) : IPermissionBackend {

    override fun backendId(): String = BACKEND_ID

    override fun isAvailable(): Boolean = available()

    override fun check(playerId: PlayerId, node: String): Boolean = false

    override fun rolesOf(playerId: PlayerId): Set<RoleId> = emptySet()

    override fun grant(
        playerId: PlayerId,
        roleId: RoleId,
        duration: Duration?,
        grantedBy: PlayerId?,
    ): Result<Unit, RoleError> = Result.failure(RoleError.BackendUnavailable(BACKEND_ID))

    override fun revoke(
        playerId: PlayerId,
        roleId: RoleId,
        revokedBy: PlayerId?,
    ): Result<Unit, RoleError> = Result.failure(RoleError.BackendUnavailable(BACKEND_ID))

    public companion object {
        public const val BACKEND_ID: String = "luckperms"
    }
}
