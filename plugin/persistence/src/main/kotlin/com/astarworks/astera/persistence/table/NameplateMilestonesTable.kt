package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `nameplate_milestones` table — Wave 5flex / ADR-0102 surface #5
 * (V36 migration).
 *
 * Per-player current display milestone. Wave 5flex's nameplate cache
 * is in-memory; this table persists *only* the currently-displayed
 * milestone so a server restart preserves the player's prestige
 * highlight on their next join.
 */
public object NameplateMilestonesTable : Table("nameplate_milestones") {
    public val playerId = javaUUID("player_id")
    public val achievementId = varchar("achievement_id", 128).nullable()
    public val milestoneKey = varchar("milestone_key", 192).nullable()
    public val setAt = timestamp("set_at")

    override val primaryKey: PrimaryKey = PrimaryKey(playerId)
}
