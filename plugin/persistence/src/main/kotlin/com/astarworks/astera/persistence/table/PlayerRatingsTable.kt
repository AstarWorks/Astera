package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * `player_ratings` table — per-player global Elo rating (Wave 7b, ADR-0037,
 * V47 migration).
 *
 * Columnar (no JSONB): a single bounded int per player, queried by PK. Mirrors
 * [com.astarworks.astera.match.domain.rating.Rating] (stored as a plain int —
 * the value class does not cross the persistence boundary). One row per player.
 */
public object PlayerRatingsTable : Table("player_ratings") {
    public val playerId = javaUUID("player_id")
    public val rating = integer("rating")
    public val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }
    override val primaryKey: PrimaryKey = PrimaryKey(playerId)
}
