package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * `player_advancement_progress` table (Wave 5p, ADR-0088).
 *
 * One row per `(player_id, advancement_id)` tuple. `current_count` advances
 * toward `target_count`; `completed_at` latches on first completion (never
 * unsets — re-grants are idempotent).
 *
 * Migration: V26.
 *
 * The use case writes both the canonical id and per-leaf sub-ids
 * (`<canonical>-l<index>`) into this table; `IAdvancementService.completed()`
 * filters the sub-ids out for HUD display.
 */
internal object PlayerAdvancementProgressTable : Table("player_advancement_progress") {
    val playerId = javaUUID("player_id")
    val advancementId = varchar("advancement_id", 128)
    val currentCount = integer("current_count").default(0)
    val targetCount = integer("target_count")
    val completedAt = timestamp("completed_at").nullable()
    val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }
    override val primaryKey: PrimaryKey = PrimaryKey(playerId, advancementId)
}
