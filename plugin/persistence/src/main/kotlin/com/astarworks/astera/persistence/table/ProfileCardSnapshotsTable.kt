package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `profile_card_snapshots` table — Wave 5flex / ADR-0102 surface #3
 * (V35 migration).
 *
 * One row per player. Each contributing plugin owns one *summary*
 * (count + featured-id) column; the recomputation use case writes the
 * whole row atomically.
 */
public object ProfileCardSnapshotsTable : Table("profile_card_snapshots") {
    public val playerId = javaUUID("player_id")
    public val displayName = varchar("display_name", 64)
    public val visibility = varchar("visibility", 16)
    public val equipmentItemCount = integer("equipment_item_count")
    public val equipmentFeaturedId = varchar("equipment_featured_id", 128).nullable()
    public val titlesUnlockedCount = integer("titles_unlocked_count")
    public val titlesEquippedId = varchar("titles_equipped_id", 128).nullable()
    public val cosmeticsUnlockedCount = integer("cosmetics_unlocked_count")
    public val cosmeticsFeaturedId = varchar("cosmetics_featured_id", 128).nullable()
    public val powerIndexTotal = long("power_index_total")
    public val computedAt = timestamp("computed_at")

    override val primaryKey: PrimaryKey = PrimaryKey(playerId)
}
