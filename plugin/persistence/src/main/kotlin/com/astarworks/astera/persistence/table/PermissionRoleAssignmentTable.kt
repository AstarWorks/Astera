package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Wave 5t (ADR-0086) — V22 migration table.
 *
 * Backs [com.astarworks.astera.core.application.service.permission.MinimalPermissionBackend]
 * — the richer role-assignment storage with `expiresAt` + `grantedBy`.
 * Coexists with [RoleAssignmentsTable] (Wave 3 / V4) which backs the legacy
 * `IPermissionService`. The two surfaces are intentionally separate per
 * Wave 5t / ADR-0086 §"Persistence shape".
 */
public object PermissionRoleAssignmentTable : Table("permission_role_assignment") {
    public val playerId = javaUUID("player_id")
    public val roleId = varchar("role_id", 128)
    public val grantedAt = timestamp("granted_at")
    public val expiresAt = timestamp("expires_at").nullable()
    public val grantedBy = javaUUID("granted_by").nullable()
    override val primaryKey: PrimaryKey = PrimaryKey(playerId, roleId)
}
