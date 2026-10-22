package com.astarworks.astera.core.domain.vault

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * The player's personal vault = the ender chest (WP12 inventory model).
 *
 * Inventory model, one concept each:
 *  - **loadout** ([com.astarworks.astera.core.domain.player.Loadout]): pre-match kit selection.
 *  - **inventory snapshot** ([com.astarworks.astera.core.domain.player.PlayerInventorySnapshot]):
 *    per-match save on entry / restore on exit.
 *  - **personal vault** (this type): persistent storage the player opens via `/vault`
 *    or a vanilla ender chest block, with free item movement like a chest.
 *
 * Sparse slot model: [items] only lists occupied slots (`0 until [SLOT_COUNT]`),
 * each slot at most once. [VaultItem.nbt] carries the opaque host-serialized
 * stack (Base64) so the Paper adapter can round-trip arbitrary item state;
 * [VaultItem.itemId] is the readable id used for display / valuation.
 */
public data class PersonalVault(
    val playerId: PlayerId,
    val items: List<VaultItem>,
) {
    init {
        val slots = items.map { it.slot }
        require(slots.toSet().size == slots.size) { "PersonalVault has duplicate slots: $slots" }
    }

    public fun itemAt(slot: Int): VaultItem? = items.firstOrNull { it.slot == slot }

    public data class VaultItem(
        val slot: Int,
        val itemId: String,
        val count: Int,
        val nbt: String? = null,
    ) {
        init {
            require(count >= 1) { "VaultItem.count must be >= 1: $count" }
            require(slot in 0 until SLOT_COUNT) { "VaultItem.slot must be in 0 until $SLOT_COUNT: $slot" }
        }
    }

    public companion object {
        /** Vanilla ender chest size (3 rows). */
        public const val SLOT_COUNT: Int = 27

        public fun empty(playerId: PlayerId): PersonalVault =
            PersonalVault(playerId, emptyList())
    }
}
