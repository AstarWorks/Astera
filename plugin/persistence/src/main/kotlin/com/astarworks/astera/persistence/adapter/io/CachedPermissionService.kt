package com.astarworks.astera.persistence.adapter.io

import com.astarworks.astera.core.application.port.outbound.*
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.permission.*
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.repository.PostgresPermissionService
import java.util.concurrent.ConcurrentHashMap

/** Permission decisions must finish on the event's owner before event cancellation is inspected. */
public class CachedPermissionService(private val storage: PostgresPermissionService, private val writes: WriteBehind) : IPermissionService {
    private val definitions = storage.allDefinitions().associateBy { it.id }
    private val roles = ConcurrentHashMap<PlayerId, Set<RoleId>>()
    public fun preload(id: PlayerId) { roles[id] = storage.rolesOf(id) }
    public fun evict(id: PlayerId) { roles.remove(id) }
    override fun rolesOf(playerId: PlayerId): Set<RoleId> = roles[playerId].orEmpty()
    override fun definitionOf(roleId: RoleId): RoleDefinition? = definitions[roleId]
    override fun allDefinitions(): Collection<RoleDefinition> = definitions.values
    override fun check(playerId: PlayerId, permission: Permission): Boolean = rolesOf(playerId).any { id ->
        definitions[id]?.permissions?.any { it.id == "*" || it == permission } == true
    }
    override fun assign(playerId: PlayerId, roleId: RoleId): Result<Unit, AssignRoleError> {
        if (roleId !in definitions) return Result.failure(AssignRoleError.RoleNotFound(roleId))
        if (roleId in rolesOf(playerId)) return Result.failure(AssignRoleError.AlreadyAssigned(playerId, roleId))
        roles[playerId] = rolesOf(playerId) + roleId
        writes.enqueue(Triple("role", playerId, roleId)) { storage.assign(playerId, roleId) }
        return Result.success(Unit)
    }
    override fun revoke(playerId: PlayerId, roleId: RoleId): Result<Unit, RevokeRoleError> {
        if (roleId !in rolesOf(playerId)) return Result.failure(RevokeRoleError.NotAssigned(playerId, roleId))
        roles[playerId] = rolesOf(playerId) - roleId
        writes.enqueue(Triple("role", playerId, roleId)) { storage.revoke(playerId, roleId) }
        return Result.success(Unit)
    }
}
