package com.astarworks.astera.core.domain.player

import java.time.Instant

/**
 * Frozen capture of a player's full Bukkit inventory (Wave 5e, RTM issue #5).
 *
 * Persistence target (V10 `player_inventory_snapshots`): one row per player,
 * latest-wins (no history). Wave 5e captures + restores the four canonical
 * regions: hotbar [9] / main [27] / offhand [1] / armor [4]. Wave 6b's
 * `PaperInventorySnapshotSerializer` lifts/lowers between Bukkit
 * `PlayerInventory` and this vendor-neutral form via `BukkitObjectOutputStream`.
 *
 * `version` bumps when [InventoryItemSlot] field shape changes incompatibly
 * — additive optional fields do not require a bump (matches `DomainEvent`
 * convention).
 *
 * During restore, `PlayerSession.state` is [SessionState.Loading] so the
 * Bukkit-side action lock (Wave 6b) cancels click / pickup / interact.
 */
public data class PlayerInventorySnapshot(
    val playerId: PlayerId,
    val hotbar: List<InventoryItemSlot?>,
    val main: List<InventoryItemSlot?>,
    val offhand: InventoryItemSlot?,
    val armor: List<InventoryItemSlot?>,
    val version: Int,
    val savedAt: Instant,
) {
    init {
        require(hotbar.size == 9) { "hotbar must have 9 slots, got ${hotbar.size}" }
        require(main.size == 27) { "main must have 27 slots, got ${main.size}" }
        require(armor.size == 4) { "armor must have 4 slots, got ${armor.size}" }
    }

    public companion object {
        public const val CURRENT_VERSION: Int = 1

        /**
         * All-empty snapshot for [playerId] (hotbar/main/offhand/armor cleared).
         *
         * Wave 5e uses this as the placeholder shape published by
         * `SaveInventoryOnMatchEndedListener` — the listener has no Paper
         * inventory access yet, so it stores a "clean slate" snapshot per
         * participant on `MatchEnded`. Wave 6b replaces the listener with a
         * real `BukkitObjectOutputStream` capture and this factory becomes a
         * convenience used by tests + admin reset only.
         */
        public fun empty(playerId: PlayerId, savedAt: Instant = Instant.now()): PlayerInventorySnapshot =
            PlayerInventorySnapshot(
                playerId = playerId,
                hotbar = List(9) { null },
                main = List(27) { null },
                offhand = null,
                armor = List(4) { null },
                version = CURRENT_VERSION,
                savedAt = savedAt,
            )
    }
}

/**
 * Vendor-neutral single inventory slot record (Wave 5e).
 *
 * Wave 6b's Paper serializer converts to/from `ItemStack` via
 * `BukkitObjectOutputStream` (Base64-wrapped in [serializedItemStack]).
 * The Wave 5e domain only requires opaque pass-through.
 */
public sealed interface InventoryItemSlot {
    /**
     * Item identified by content-pack id (e.g., `astera:example-pistol`)
     * plus a count. No NBT detail; sufficient for Wave 5e tests and the
     * lobby-side restore happy path.
     */
    public data class ContentItem(val itemId: String, val count: Int) : InventoryItemSlot {
        init { require(count in 1..64) { "ContentItem.count must be in 1..64: $count" } }
    }

    /**
     * Opaque serialized Bukkit ItemStack. Wave 6b populates this from
     * `BukkitObjectOutputStream` → Base64. Wave 5e domain treats as black
     * box for round-trip tests.
     */
    public data class SerializedBukkit(val serializedItemStack: String) : InventoryItemSlot
}
