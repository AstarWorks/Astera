package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.AssignRoleError
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.RevokeRoleError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.permission.DefaultRoles
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.permission.RoleDefinition
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/** Thread-safe in-memory implementation of [IPermissionService]. Seeded with [DefaultRoles.builtin()]. */
public class InMemoryPermissionService : IPermissionService {

    private val definitions: ConcurrentHashMap<RoleId, RoleDefinition> = ConcurrentHashMap<RoleId, RoleDefinition>().also { map ->
        DefaultRoles.builtin().forEach { map[it.id] = it }
    }

    private val assignments: ConcurrentHashMap<PlayerId, MutableSet<RoleId>> = ConcurrentHashMap()

    override fun assign(playerId: PlayerId, roleId: RoleId): Result<Unit, AssignRoleError> {
        if (!definitions.containsKey(roleId)) {
            return Result.failure(AssignRoleError.RoleNotFound(roleId))
        }
        val roles = assignments.computeIfAbsent(playerId) { java.util.Collections.newSetFromMap(ConcurrentHashMap()) }
        val added = roles.add(roleId)
        return if (added) {
            Result.success(Unit)
        } else {
            Result.failure(AssignRoleError.AlreadyAssigned(playerId, roleId))
        }
    }

    override fun revoke(playerId: PlayerId, roleId: RoleId): Result<Unit, RevokeRoleError> {
        val roles = assignments[playerId]
        val removed = roles?.remove(roleId) ?: false
        return if (removed) {
            Result.success(Unit)
        } else {
            Result.failure(RevokeRoleError.NotAssigned(playerId, roleId))
        }
    }

    override fun check(playerId: PlayerId, permission: Permission): Boolean {
        val roles = assignments[playerId] ?: return false
        return roles.any { roleId ->
            val def = definitions[roleId] ?: return@any false
            def.permissions.contains(Permission("*")) || def.permissions.contains(permission)
        }
    }

    override fun rolesOf(playerId: PlayerId): Set<RoleId> =
        assignments[playerId]?.toSet() ?: emptySet()

    override fun definitionOf(roleId: RoleId): RoleDefinition? = definitions[roleId]

    override fun allDefinitions(): Collection<RoleDefinition> = definitions.values.toList()
}
