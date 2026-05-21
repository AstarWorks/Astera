package com.astarworks.astera.persistence.table

import com.astarworks.astera.match.application.replay.json.KeyFramesJson
import com.astarworks.astera.match.application.replay.json.ReplayEventsJson
import com.astarworks.astera.match.application.replay.json.ReplayFormatJson
import com.astarworks.astera.persistence.jsonb.jsonb
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `match_replays` table — committed [com.astarworks.astera.core.domain
 * .replay.RecordedReplay] per match (Wave 5v / ADR-0088, V20 migration).
 *
 * One row per `matchId` (latest-write-wins via upsert). `replay_id` is a
 * per-capture UUID kept for forensic chains; `idx_match_replays_replay_id`
 * supports `findById`. `format` / `key_frames` / `events` are JSONB blobs
 * mirrored by [ReplayFormatJson] / [KeyFramesJson] / [ReplayEventsJson]
 * sealed DTOs.
 *
 * Columnar `start_tick` / `end_tick` / `size_bytes` allow admin queries
 * like `SELECT match_id FROM match_replays WHERE size_bytes > 10_000_000`
 * without parsing JSONB. `saved_at` is set by the row (DEFAULT NOW()) so
 * the application doesn't need to ship a clock to the repository for the
 * common write path.
 */
public object MatchReplaysTable : Table("match_replays") {
    public val matchId = varchar("match_id", 64)
    public val replayId = javaUUID("replay_id")
    public val startTick = long("start_tick")
    public val endTick = long("end_tick")
    public val sizeBytes = integer("size_bytes")
    public val format = jsonb("format", ReplayFormatJson.serializer())
    public val keyFrames = jsonb("key_frames", KeyFramesJson.serializer())
    public val events = jsonb("events", ReplayEventsJson.serializer())
    public val savedAt = timestamp("saved_at").clientDefault { java.time.Instant.now() }
    override val primaryKey: PrimaryKey = PrimaryKey(matchId)
}
