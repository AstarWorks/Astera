package com.astarworks.astera.items.domain.refinement

import java.util.UUID

/**
 * Opaque identifier for a per-player [ItemInstance] (Wave 5refine, ADR-0100).
 *
 * Wraps a UUID rather than a kebab-case string because instances are produced
 * by the server at run time — there is no human-authored id (contrast with
 * [com.astarworks.astera.core.domain.item.ItemId] which originates from
 * `content/items/`). The persistence column type is `UUID` (V30 migration),
 * so the value-class representation matches storage one-to-one.
 *
 * # When created
 *
 *   - First refinement attempt on a raw item template (auto-promotion from a
 *     plain `ItemSpec` to a tracked [ItemInstance]).
 *   - Drop-table / loot service grants an item with a non-empty initial roll.
 *
 * The id outlives the player's inventory: rerolls / re-listings on the
 * marketplace (ADR-0035 §"refined item flipping") keep the same id so audit
 * trails remain joinable.
 */
@JvmInline
public value class ItemInstanceId(public val value: UUID) {
    override fun toString(): String = value.toString()

    public companion object {
        /** Fresh id backed by `UUID.randomUUID()`. */
        public fun random(): ItemInstanceId = ItemInstanceId(UUID.randomUUID())

        public fun parse(s: String): ItemInstanceId = ItemInstanceId(UUID.fromString(s))
    }
}
