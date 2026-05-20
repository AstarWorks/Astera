package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Wave 5t (ADR-0086) — V24 migration table. Player feedback queue.
 *
 * `category_key` is `FeedbackCategory.key` ("cheating" / "bug" / …).
 * `category_freeform` is non-null only for `Other(text)`. `status` is the
 * lower-snake-case name of [com.astarworks.astera.core.domain.feedback.FeedbackStatus]
 * stored as text — adding a new status variant should never require an
 * `ALTER TYPE`.
 */
public object PlayerFeedbackTable : Table("player_feedback") {
    public val id = javaUUID("id")
    public val submittedBy = javaUUID("submitted_by")
    public val targetPlayerId = javaUUID("target_player_id").nullable()
    public val categoryKey = varchar("category_key", 32)
    public val categoryFreeform = text("category_freeform").nullable()
    public val comment = text("comment")
    public val submittedAt = timestamp("submitted_at")
    public val status = varchar("status", 16)
    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
