package com.astarworks.astera.core.domain.vault

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Persistent per-player item storage (Wave 5e, RTM issue #3).
 *
 * Separate from [com.astarworks.astera.core.domain.player.LoadoutSlot]
 * (Wave 4.5) and from [com.astarworks.astera.core.domain.player.PlayerInventorySnapshot]
 * (the Bukkit-inventory carryover): this is the "EnderChest equivalent"
 * — opt-in storage the player explicitly opens to deposit/withdraw items.
 *
 * Worker ε fills the slot semantics (page navigation, max capacity). Phase A
 * declares the value type with a generic `items` slot list keyed by content
 * itemId + count. Storage backing is V7 JSONB.
 */
public data class PersonalVault(
    val playerId: PlayerId,
    val items: List<VaultItem>,
) {
    public data class VaultItem(
        val itemId: String,
        val count: Int,
        val nbt: String? = null,
    ) {
        init {
            require(count >= 1) { "VaultItem.count must be >= 1: $count" }
        }
    }

    public companion object {
        public fun empty(playerId: PlayerId): PersonalVault =
            PersonalVault(playerId, emptyList())
    }
}
