package com.astarworks.astera.persistence.table

import com.astarworks.astera.persistence.jsonb.PlayerSessionJson
import com.astarworks.astera.persistence.jsonb.jsonb
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `player_sessions` table — per-player runtime session state (Wave _persist-1
 * / ADR-0068, V11 migration).
 *
 * One row per player; latest-write-wins (no history). `session` JSONB carries
 * the [com.astarworks.astera.core.domain.player.SessionState] discriminator
 * (Lobby / InMatch / Spectator / Dead / Disconnected / Loading) plus the
 * optional [com.astarworks.astera.core.domain.match.MatchId] for cross-server
 * traversal. `updated_at` is written on every upsert so admin queries can
 * detect stale rows without parsing JSONB.
 *
 * Storage choice (ADR-0014): the session is a bounded, read-whole aggregate
 * (one row per player) — JSONB beats a columnar split for a 2-field document
 * that's almost always read or written wholesale.
 */
public object PlayerSessionsTable : Table("player_sessions") {
    public val playerId = javaUUID("player_id")
    public val session = jsonb("session", PlayerSessionJson.serializer())
    public val updatedAt = timestamp("updated_at")
    override val primaryKey: PrimaryKey = PrimaryKey(playerId)
}
