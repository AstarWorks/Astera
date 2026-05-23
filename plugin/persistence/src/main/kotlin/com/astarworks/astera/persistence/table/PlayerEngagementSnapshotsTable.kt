package com.astarworks.astera.persistence.table

import com.astarworks.astera.persistence.jsonb.EngagementSnapshotJson
import com.astarworks.astera.persistence.jsonb.jsonb
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `player_engagement_snapshots` table — per-player engagement state
 * (Wave 5engage / ADR-0104, V30 migration).
 *
 * One row per player; latest-write-wins. `snapshot` JSONB carries the
 * [com.astarworks.astera.core.domain.engagement.EngagementSnapshot] payload
 * (raw inputs + derived score). `computed_at` lives on the row so admin
 * queries can filter on staleness without parsing JSONB.
 *
 * Storage choice (ADR-0014): a per-player aggregate that is always read /
 * written wholesale + bounded in size (≤ ~200 bytes) → JSONB beats a
 * columnar split. Matches the `player_sessions` / `player_settings`
 * precedent (Wave _persist-1 / Wave 5e).
 */
public object PlayerEngagementSnapshotsTable : Table("player_engagement_snapshots") {
    public val playerId = javaUUID("player_id")
    public val snapshot = jsonb("snapshot", EngagementSnapshotJson.serializer())
    public val computedAt = timestamp("computed_at")
    override val primaryKey: PrimaryKey = PrimaryKey(playerId)
}
