package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * `norma_progress` table — per-player mission progress.
 *
 * Wave 4.5 (R3.5) / ADR-0027: dailies / weeklies need to survive restarts.
 * One row per `(player_id, norma_id)`; `current_count` accumulates toward the
 * norma's target, `completed=true` latches when the threshold is crossed.
 *
 * Period rollover (resetting dailies at 00:00 server-time, weeklies on Monday,
 * …) is handled by the Stats plugin's scheduler in Phase C; this table is
 * just the raw progress store.
 */
internal object NormaProgressTable : Table("norma_progress") {
    val playerId = javaUUID("player_id")
    val normaId = varchar("norma_id", 128)
    val currentCount = long("current_count")
    val completed = bool("completed").default(false)
    val completedAt = timestamp("completed_at").nullable()
    val periodKey = text("period_key").default("")
    val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }
    override val primaryKey: PrimaryKey = PrimaryKey(playerId, normaId)
}
