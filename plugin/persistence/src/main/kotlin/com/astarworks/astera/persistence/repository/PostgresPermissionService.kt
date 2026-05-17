package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.AssignRoleError
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.RevokeRoleError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.permission.DefaultRoles
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.permission.RoleDefinition
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.jsonb.PermissionsJson
import com.astarworks.astera.persistence.table.RoleAssignmentsTable
import com.astarworks.astera.persistence.table.RoleDefinitionsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Postgres-backed [IPermissionService]. Registered by AsteraPersistencePlugin
 * at priority=100, overlays the [com.astarworks.astera.core.application.service.InMemoryPermissionService]
 * default registered by the core platform.
 *
 * On construction, seeds `role_definitions` from [DefaultRoles.builtin] via
 * `UPSERT` — idempotent on PK conflict and keeps stored permission sets in
 * sync with code changes across deploys.
 *
 * `check()` uses an in-memory containment scan: it loads the player's roles
 * and their permission sets (typically < 50 entries per role, 1-3 roles per
 * player) and tests for `permission` or the `*` wildcard. Phase 1 acceptable
 * per the parallel-worker scope brief; if the working set grows we can swap
 * to a JOIN with `permissions @> '["…"]'` JSONB containment.
 */
public class PostgresPermissionService(private val db: Database) : IPermissionService {

    init {
        transaction(db) {
            DefaultRoles.builtin().forEach { def ->
                RoleDefinitionsTable.upsert(RoleDefinitionsTable.id) {
                    it[id] = def.id.value
                    it[displayName] = def.displayName
                    it[permissions] = PermissionsJson.fromDomain(def.permissions)
                }
            }
        }
    }

    override fun assign(playerId: PlayerId, roleId: RoleId): Result<Unit, AssignRoleError> = transaction(db) {
        val exists = RoleDefinitionsTable
            .selectAll()
            .where { RoleDefinitionsTable.id eq roleId.value }
            .limit(1)
            .any()
        if (!exists) {
            return@transaction Result.failure(AssignRoleError.RoleNotFound(roleId))
        }
        val inserted = RoleAssignmentsTable.insertIgnore {
            it[RoleAssignmentsTable.playerId] = playerId.uuid
            it[RoleAssignmentsTable.roleId] = roleId.value
            // granted_at has a clientDefault, no need to set explicitly.
        }.insertedCount
        if (inserted > 0) {
            Result.success(Unit)
        } else {
            Result.failure(AssignRoleError.AlreadyAssigned(playerId, roleId))
        }
    }

    override fun revoke(playerId: PlayerId, roleId: RoleId): Result<Unit, RevokeRoleError> = transaction(db) {
        val deleted = RoleAssignmentsTable.deleteWhere {
            (RoleAssignmentsTable.playerId eq playerId.uuid) and (RoleAssignmentsTable.roleId eq roleId.value)
        }
        if (deleted > 0) {
            Result.success(Unit)
        } else {
            Result.failure(RevokeRoleError.NotAssigned(playerId, roleId))
        }
    }

    override fun check(playerId: PlayerId, permission: Permission): Boolean = transaction(db) {
        val wildcard = Permission("*")
        (RoleAssignmentsTable innerJoin RoleDefinitionsTable)
            .select(RoleDefinitionsTable.permissions)
            .where { RoleAssignmentsTable.playerId eq playerId.uuid }
            .any { row ->
                val perms = row[RoleDefinitionsTable.permissions].toDomain()
                wildcard in perms || permission in perms
            }
    }

    override fun rolesOf(playerId: PlayerId): Set<RoleId> = transaction(db) {
        RoleAssignmentsTable
            .select(RoleAssignmentsTable.roleId)
            .where { RoleAssignmentsTable.playerId eq playerId.uuid }
            .map { RoleId(it[RoleAssignmentsTable.roleId]) }
            .toSet()
    }

    override fun definitionOf(roleId: RoleId): RoleDefinition? = transaction(db) {
        RoleDefinitionsTable
            .selectAll()
            .where { RoleDefinitionsTable.id eq roleId.value }
            .firstOrNull()
            ?.toDomain()
    }

    override fun allDefinitions(): Collection<RoleDefinition> = transaction(db) {
        RoleDefinitionsTable.selectAll().map { it.toDomain() }
    }

    private fun ResultRow.toDomain(): RoleDefinition = RoleDefinition(
        id = RoleId(this[RoleDefinitionsTable.id]),
        displayName = this[RoleDefinitionsTable.displayName],
        permissions = this[RoleDefinitionsTable.permissions].toDomain(),
    )
}
