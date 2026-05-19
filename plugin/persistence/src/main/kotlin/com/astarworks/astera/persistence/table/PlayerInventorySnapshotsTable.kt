package com.astarworks.astera.persistence.table

import com.astarworks.astera.persistence.jsonb.PlayerInventorySnapshotJson
import com.astarworks.astera.persistence.jsonb.jsonb
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `player_inventory_snapshots` table — per-player frozen Bukkit-inventory
 * capture (Wave 5e / RTM issue #5, V10 migration).
 *
 * One row per player; latest-write-wins (no history). `snapshot` JSONB carries
 * the four canonical regions (hotbar [9] / main [27] / offhand [1] /
 * armor [4]) per [PlayerInventorySnapshotJson]. `version` mirrors
 * [com.astarworks.astera.core.domain.player.PlayerInventorySnapshot.Companion.CURRENT_VERSION];
 * `saved_at` is set explicitly by the use case (clock-injectable) rather than
 * by `clientDefault` so deterministic tests can pin timestamps.
 *
 * Storage choice (ADR-0014): the snapshot is a bounded, read-whole aggregate
 * (one row decoded as a single document on join/respawn) — JSONB beats a
 * 41-row child table for both read and write paths.
 */
public object PlayerInventorySnapshotsTable : Table("player_inventory_snapshots") {
    public val playerId = javaUUID("player_id")
    public val snapshot = jsonb("snapshot", PlayerInventorySnapshotJson.serializer())
    public val version = integer("version")
    public val savedAt = timestamp("saved_at")
    override val primaryKey: PrimaryKey = PrimaryKey(playerId)
}
