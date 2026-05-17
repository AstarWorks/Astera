package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * `role_assignments` table — links `player_id` to `role_id` (composite PK).
 * Wave 3 (R2) / ADR-0020. `role_id` FK references [RoleDefinitionsTable.id].
 *
 * `granted_at` is informational only (audit / debugging); not used by
 * permission checks. Multiple roles per player are supported.
 */
public object RoleAssignmentsTable : Table("role_assignments") {
    public val playerId = javaUUID("player_id")
    public val roleId = varchar("role_id", 128).references(RoleDefinitionsTable.id)
    public val grantedAt = timestamp("granted_at").clientDefault { Instant.now() }
    override val primaryKey: PrimaryKey = PrimaryKey(playerId, roleId)
}
