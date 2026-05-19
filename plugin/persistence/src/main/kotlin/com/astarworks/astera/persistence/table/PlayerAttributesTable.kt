package com.astarworks.astera.persistence.table

import com.astarworks.astera.persistence.jsonb.PlayerAttributesJson
import com.astarworks.astera.persistence.jsonb.jsonb
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * `player_attributes` table — per-player unified attribute container
 * (Wave 5e / ADR-0058 §5, V14 migration).
 *
 * Single JSONB column [payload] carries both stored values and the
 * (immutable, FIFO) modifier list via [PlayerAttributesJson]. One row per
 * player; the row is the canonical home for `STAT_*` / `WALLET_*` /
 * `RESOURCE_MANA_*` etc.
 */
public object PlayerAttributesTable : Table("player_attributes") {
    public val playerId = javaUUID("player_id")
    public val payload = jsonb("payload", PlayerAttributesJson.serializer())
    public val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }
    override val primaryKey: PrimaryKey = PrimaryKey(playerId)
}
