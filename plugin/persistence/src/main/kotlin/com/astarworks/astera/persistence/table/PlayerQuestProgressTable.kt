package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * `player_quest_progress` table (Wave 5s, ADR-0091).
 *
 * One row per `(player_id, chain_id)` tuple. `current_stage_id` is the active
 * stage; `stage_progress` is the cumulative counter against the active stage's
 * criteria target. On stage transitions the row is overwritten with the next
 * stage's id and `stage_progress=0`. `completed_at` latches on chain completion
 * (never unsets — re-completions are idempotent).
 *
 * Migration: V27.
 */
internal object PlayerQuestProgressTable : Table("player_quest_progress") {
    val playerId = javaUUID("player_id")
    val chainId = varchar("chain_id", 128)
    val currentStageId = varchar("current_stage_id", 128)
    val stageProgress = integer("stage_progress").default(0)
    val completedAt = timestamp("completed_at").nullable()
    val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }
    override val primaryKey: PrimaryKey = PrimaryKey(playerId, chainId)
}
