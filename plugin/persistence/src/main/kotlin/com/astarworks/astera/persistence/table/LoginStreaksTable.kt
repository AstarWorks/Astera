package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/** `login_streaks` (V61, ADR-0210) — daily login-bonus streak per player. */
public object LoginStreaksTable : Table("login_streaks") {
    public val playerId = javaUUID("player_id")
    public val streakDays = integer("streak_days")
    public val lastClaimDate = date("last_claim_date")
    public val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }
    override val primaryKey: PrimaryKey = PrimaryKey(playerId)
}
