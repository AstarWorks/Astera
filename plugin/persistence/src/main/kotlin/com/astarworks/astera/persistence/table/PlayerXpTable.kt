package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/** `player_xp` (V50, ADR-0187) — lifetime XP total per player; level is derived by `XpCurve`. */
public object PlayerXpTable : Table("player_xp") {
    public val playerId = javaUUID("player_id")
    public val totalXp = long("total_xp").default(0L)
    public val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }
    override val primaryKey: PrimaryKey = PrimaryKey(playerId)
}
