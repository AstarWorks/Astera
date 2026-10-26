package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot

/**
 * Outbound port: per-player Bukkit-inventory snapshot (Wave 5e, RTM issue #5).
 *
 * Backed by V10 `player_inventory_snapshots` (player_id PK, snapshot JSONB,
 * version INT, saved_at TIMESTAMP) — latest-wins (no history). Wave 6b's
 * `PaperInventorySnapshotSerializer` lifts/lowers via
 * `BukkitObjectOutputStream` Base64 wrap; the Wave 5e domain treats slots
 * as opaque [InventoryItemSlot].
 */
public interface IPlayerInventorySnapshotRepository {

    /** Persist [snapshot] — overwrites any prior row for the player. */
    public fun save(snapshot: PlayerInventorySnapshot)

    /** Most recent snapshot for [playerId], or `null` if none saved. */
    public fun findLatest(playerId: PlayerId): PlayerInventorySnapshot?

    /** Forget the snapshot for [playerId] (admin reset). */
    public fun delete(playerId: PlayerId)
}
