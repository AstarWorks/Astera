package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.AssignRoleError
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.RevokeRoleError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.permission.RoleDefinition
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * [IPermissionService] stub for admin-panel tests (F1-D): [check] is true for
 * every player in [admins] (any permission) and false for everyone else.
 */
public class FakeAdminPermissionService(
    public val admins: MutableSet<PlayerId> = mutableSetOf(),
) : IPermissionService {

    override fun assign(playerId: PlayerId, roleId: RoleId): Result<Unit, AssignRoleError> = Result.success(Unit)

    override fun revoke(playerId: PlayerId, roleId: RoleId): Result<Unit, RevokeRoleError> = Result.success(Unit)

    override fun check(playerId: PlayerId, permission: Permission): Boolean = playerId in admins

    override fun rolesOf(playerId: PlayerId): Set<RoleId> = emptySet()

    override fun definitionOf(roleId: RoleId): RoleDefinition? = null

    override fun allDefinitions(): Collection<RoleDefinition> = emptyList()
}
