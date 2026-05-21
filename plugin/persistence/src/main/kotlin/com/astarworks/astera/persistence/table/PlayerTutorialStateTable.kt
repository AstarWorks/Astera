package com.astarworks.astera.persistence.table

import com.astarworks.astera.persistence.jsonb.TutorialIdsJson
import com.astarworks.astera.persistence.jsonb.jsonb
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `player_tutorial_state` table — per-player tutorial completion / skip /
 * cursor state (Wave 5h, ADR-0079 §3, V20 migration).
 *
 * One row per player; latest-write-wins (no history). The two JSONB columns
 * each carry a sorted array of [com.astarworks.astera.core.domain.tutorial.TutorialScriptId]
 * strings; [currentTutorial] + [currentStep] are the in-flight cursor and are
 * both nullable (both null when no tutorial is running).
 *
 * Storage choice (ADR-0014): the tutorial state is a bounded, read-whole
 * aggregate (one row per player, two short id sets + a short cursor) — JSONB
 * beats a columnar split for a document that's almost always read or written
 * wholesale on join / advance.
 */
public object PlayerTutorialStateTable : Table("player_tutorial_state") {
    public val playerId = javaUUID("player_id")
    public val completedTutorials = jsonb("completed_tutorials", TutorialIdsJson.serializer())
    public val skippedTutorials = jsonb("skipped_tutorials", TutorialIdsJson.serializer())
    public val currentTutorial = varchar("current_tutorial", 64).nullable()
    public val currentStep = integer("current_step").nullable()
    public val updatedAt = timestamp("updated_at")
    override val primaryKey: PrimaryKey = PrimaryKey(playerId)
}
