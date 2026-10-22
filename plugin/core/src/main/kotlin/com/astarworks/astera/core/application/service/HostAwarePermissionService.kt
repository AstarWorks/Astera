package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.AssignRoleError
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.RevokeRoleError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.permission.RoleDefinition
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * [IPermissionService] that also honours the host's own permission system
 * (ADR-0184).
 *
 * Astera's role table starts empty — nobody is assigned a role at join — so on
 * its own `check` denies every server operator, and `/astera admin promote`
 * (which needs a permission itself) could never be used to bootstrap. This
 * decorator adds the host's answer ([hostHas]: Paper `hasPermission`, which is
 * `true` for operators and honours LuckPerms-style nodes) to the role lookup.
 * Roles still work exactly as before; the host can only grant more.
 */
public class HostAwarePermissionService(
    private val delegate: IPermissionService,
    private val hostHas: (PlayerId, String) -> Boolean,
) : IPermissionService {

    override fun assign(playerId: PlayerId, roleId: RoleId): Result<Unit, AssignRoleError> =
        delegate.assign(playerId, roleId)

    override fun revoke(playerId: PlayerId, roleId: RoleId): Result<Unit, RevokeRoleError> =
        delegate.revoke(playerId, roleId)

    override fun check(playerId: PlayerId, permission: Permission): Boolean =
        delegate.check(playerId, permission) || hostHas(playerId, permission.id)

    override fun rolesOf(playerId: PlayerId): Set<RoleId> = delegate.rolesOf(playerId)

    override fun definitionOf(roleId: RoleId): RoleDefinition? = delegate.definitionOf(roleId)

    override fun allDefinitions(): Collection<RoleDefinition> = delegate.allDefinitions()
}
