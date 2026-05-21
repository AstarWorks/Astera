package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `region_permission` table — per-region permission rules consumed by
 * [com.astarworks.astera.core.application.port.outbound.IRegionPermissionService]
 * (Wave 5n, V25 migration).
 *
 * The natural key is `(region_id, permission_key, player_id)` where
 * `player_id` is nullable for "everyone" rules. Postgres treats `NULL` as
 * distinct in unique constraints, so V25 uses two partial unique indices
 * (`WHERE player_id IS NOT NULL` and `WHERE player_id IS NULL`) instead of
 * one PK. This Exposed mapping therefore does NOT declare a `primaryKey` —
 * uniqueness is enforced at the migration layer.
 *
 * `permission_key` encodes [com.astarworks.astera.core.domain.region.RegionPermission]:
 * - Singleton variants (Build / Break / Interact / PvP / Pickup / Drop) →
 *   their lowercase name.
 * - `Use(itemId)` → `"use:<itemId>"`.
 *
 * `scope` is `"allow"` or `"deny"`. The Postgres impl prefers the most
 * specific matching row (player-specific over everyone) at read time.
 */
public object RegionPermissionTable : Table("region_permission") {
    public val regionId = varchar("region_id", 64)
    public val permissionKey = varchar("permission_key", 96)
    public val scope = varchar("scope", 8)
    public val playerId = javaUUID("player_id").nullable()
    public val updatedAt = timestamp("updated_at")
}
