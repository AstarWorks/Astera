package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.permission.RoleDefinition
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId

/** Outbound port for role-based permission management. */
public interface IPermissionService {

    public fun assign(playerId: PlayerId, roleId: RoleId): Result<Unit, AssignRoleError>

    public fun revoke(playerId: PlayerId, roleId: RoleId): Result<Unit, RevokeRoleError>

    /** Returns true when the player holds a role that grants [permission] or the wildcard "*". */
    public fun check(playerId: PlayerId, permission: Permission): Boolean

    public fun rolesOf(playerId: PlayerId): Set<RoleId>

    public fun definitionOf(roleId: RoleId): RoleDefinition?

    public fun allDefinitions(): Collection<RoleDefinition>
}

public sealed class AssignRoleError {
    public abstract val messageKey: MessageKey

    public data class RoleNotFound(val roleId: RoleId) : AssignRoleError() {
        override val messageKey: MessageKey = MessageKey("astera.permission.role_not_found")
    }

    public data class AlreadyAssigned(val playerId: PlayerId, val roleId: RoleId) : AssignRoleError() {
        override val messageKey: MessageKey = MessageKey("astera.permission.already_assigned")
    }
}

public sealed class RevokeRoleError {
    public abstract val messageKey: MessageKey

    public data class NotAssigned(val playerId: PlayerId, val roleId: RoleId) : RevokeRoleError() {
        override val messageKey: MessageKey = MessageKey("astera.permission.not_assigned")
    }
}
