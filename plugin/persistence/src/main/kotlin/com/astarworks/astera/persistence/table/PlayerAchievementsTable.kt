package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** `player_achievements` (V60, ADR-0199) — one row per (player, unlocked achievement). */
public object PlayerAchievementsTable : Table("player_achievements") {
    public val playerId = javaUUID("player_id")
    public val achievementId = varchar("achievement_id", 128)
    public val unlockedAt = timestamp("unlocked_at")
    override val primaryKey: PrimaryKey = PrimaryKey(playerId, achievementId)
}
