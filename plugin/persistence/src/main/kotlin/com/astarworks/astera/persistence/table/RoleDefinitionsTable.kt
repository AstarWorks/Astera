package com.astarworks.astera.persistence.table

import com.astarworks.astera.persistence.jsonb.PermissionsJson
import com.astarworks.astera.persistence.jsonb.jsonb
import org.jetbrains.exposed.v1.core.Table

/**
 * `role_definitions` table — built-in and content-provided role definitions.
 * Wave 3 (R2) / ADR-0020. `permissions` is JSONB containing a sorted
 * `List<String>` of permission ids (see [PermissionsJson]).
 *
 * Seeded idempotently from [com.astarworks.astera.core.domain.permission.DefaultRoles]
 * on plugin start, so role-permission changes in code flow into the DB.
 */
public object RoleDefinitionsTable : Table("role_definitions") {
    public val id = varchar("id", 128)
    public val displayName = varchar("display_name", 256)
    public val permissions = jsonb("permissions", PermissionsJson.serializer())
    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
