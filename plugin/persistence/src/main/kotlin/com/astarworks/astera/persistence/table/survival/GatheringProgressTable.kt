package com.astarworks.astera.persistence.table.survival

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `gathering_progress` table (Wave 5pve-survival / ADR-0107, V42 migration).
 *
 * One row per
 * [com.astarworks.astera.core.domain.survival.GatheringProgress];
 * composite PK `(player_id, spot_id)` matches the natural identity.
 */
public object GatheringProgressTable : Table("gathering_progress") {
    public val playerId = javaUUID("player_id")
    public val spotId = varchar("spot_id", 128)
    public val lastHarvestedAt = timestamp("last_harvested_at")
    public val totalHarvests = long("total_harvests")

    override val primaryKey: PrimaryKey = PrimaryKey(playerId, spotId)
}
