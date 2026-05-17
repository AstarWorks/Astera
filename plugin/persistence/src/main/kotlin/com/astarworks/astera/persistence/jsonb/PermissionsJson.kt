package com.astarworks.astera.persistence.jsonb

import com.astarworks.astera.core.domain.permission.Permission
import kotlinx.serialization.Serializable

/**
 * Wraps `Set<Permission>` so the JSONB column type has a top-level serializer.
 * Domain `Permission` is a `@JvmInline value class` (no `@Serializable` annotation);
 * this DTO mirrors it as a plain `List<String>` for persistence.
 *
 * The list is stored sorted for deterministic JSONB on disk (eases diffing /
 * test assertions). Reads de-duplicate via `toSet()`.
 */
@Serializable
public data class PermissionsJson(val keys: List<String>) {

    public fun toDomain(): Set<Permission> = keys.map { Permission(it) }.toSet()

    public companion object {
        public fun fromDomain(perms: Set<Permission>): PermissionsJson =
            PermissionsJson(perms.map { it.id }.sorted())
    }
}
