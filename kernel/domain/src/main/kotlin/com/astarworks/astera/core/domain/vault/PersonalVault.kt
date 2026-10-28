package com.astarworks.astera.core.domain.vault

import com.astarworks.astera.core.domain.item.StoredItemState
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * The player's personal vault = the ender chest (WP12 inventory model).
 *
 * Inventory model, one concept each:
 *  - **loadout**: there is no separate model - the match kit is the inventory carried at entry
 *    (ADR-0203); spares live here.
 *  - **inventory snapshot** ([com.astarworks.astera.core.domain.player.PlayerInventorySnapshot]):
 *    per-match save on entry / restore on exit.
 *  - **personal vault** (this type): persistent storage the player opens via `/astera vault`
 *    or a vanilla ender chest block, with free item movement like a chest.
 *
 * Sparse slot model: [items] only lists occupied slots (`0 until [CAPACITY]`, [MAX_PAGES] pages of [PAGE_SIZE]),
 * each slot at most once. Items are stored by **id** (ADR-0302): [VaultItem.itemId] is an
 * Astera item id, or a `minecraft:` material key for vanilla items, plus [VaultItem.count] and the
 * small per-instance [VaultItem.state]; the Paper adapter rebuilds the stack from the current
 * definition on every open.
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

    /** Items of [page] with window-local slots `0 until [PAGE_SIZE]`. */
    public fun pageItems(page: Int): List<VaultItem> {
        require(page in 0 until MAX_PAGES) { "vault page must be in 0 until $MAX_PAGES: $page" }
        val base = page * PAGE_SIZE
        return items.filter { it.slot in base until base + PAGE_SIZE }.map { it.copy(slot = it.slot - base) }
    }

    /** A copy whose [page] holds exactly [local] (window-local slots); every other page is untouched. */
    public fun withPage(page: Int, local: List<VaultItem>): PersonalVault {
        require(page in 0 until MAX_PAGES) { "vault page must be in 0 until $MAX_PAGES: $page" }
        require(local.all { it.slot in 0 until PAGE_SIZE }) { "page-local slots must be in 0 until $PAGE_SIZE" }
        val base = page * PAGE_SIZE
        val kept = items.filterNot { it.slot in base until base + PAGE_SIZE }
        return PersonalVault(playerId, kept + local.map { it.copy(slot = it.slot + base) })
    }

    public data class VaultItem(
        val slot: Int,
        val itemId: String,
        val count: Int,
        val state: StoredItemState = StoredItemState.NONE,
    ) {
        init {
            require(count >= 1) { "VaultItem.count must be >= 1: $count" }
            require(slot in 0 until CAPACITY) { "VaultItem.slot must be in 0 until $CAPACITY: $slot" }
        }
    }

    public companion object {
        /** Item slots per page: five chest rows; the sixth row of the window holds the navigation buttons. */
        public const val PAGE_SIZE: Int = 45

        /** Pages a vault has. Page 0 starts with the old 27 slots, so stored contents stay valid. */
        public const val MAX_PAGES: Int = 3

        /** Total item slots (ADR-0211). */
        public const val CAPACITY: Int = PAGE_SIZE * MAX_PAGES

        public fun empty(playerId: PlayerId): PersonalVault =
            PersonalVault(playerId, emptyList())
    }
}
