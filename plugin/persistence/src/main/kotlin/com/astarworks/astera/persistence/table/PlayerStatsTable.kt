package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * `player_stats` table — per-player kill/death/playtime aggregates.
 * Wave 3 (R2) / ADR-0020. All columnar (no JSONB) — see ADR-0014: frequent
 * aggregate columns belong in their own columns, not JSON blobs.
 */
public object PlayerStatsTable : Table("player_stats") {
    public val playerId = javaUUID("player_id")
    public val kills = long("kills").default(0L)
    public val deaths = long("deaths").default(0L)
    public val assists = long("assists").default(0L)
    public val damageDealt = long("damage_dealt").default(0L)
    public val damageTaken = long("damage_taken").default(0L)
    public val playtimeSec = long("playtime_sec").default(0L)
    public val matchesPlayed = long("matches_played").default(0L)
    public val wins = long("wins").default(0L)
    public val losses = long("losses").default(0L)
    public val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }
    override val primaryKey: PrimaryKey = PrimaryKey(playerId)
}
