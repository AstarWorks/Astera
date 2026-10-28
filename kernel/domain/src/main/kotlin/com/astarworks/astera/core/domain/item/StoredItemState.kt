package com.astarworks.astera.core.domain.item

/**
 * The only per-instance state of an item that survives storage (ADR-0302): everything else on a
 * stack is derived from the item definition and rebuilt on load.
 *
 * @property damage durability damage taken (0 = pristine).
 * @property enchants vanilla enchantment key (`minecraft:sharpness`) to level.
 */
public data class StoredItemState(
    val damage: Int = 0,
    val enchants: Map<String, Int> = emptyMap(),
) {
    init { require(damage >= 0) { "StoredItemState.damage must be >= 0: $damage" } }

    public companion object {
        public val NONE: StoredItemState = StoredItemState()
    }
}
