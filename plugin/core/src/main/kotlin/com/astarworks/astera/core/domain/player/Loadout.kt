package com.astarworks.astera.core.domain.player

import com.astarworks.astera.core.domain.item.ItemId

/**
 * A player's chosen items keyed by [LoadoutSlot]. Immutable; mutation returns
 * a new instance via [with]. Slots not present in [slots] are interpreted as
 * empty.
 *
 * ADR-0022 trim: Phase 2 swaps `WeaponId` for [ItemId] so this type stays in
 * Core without pulling Weapons into the dependency closure. The Weapons
 * plugin's domain code can still ask the loadout for an [ItemId] and look up
 * the matching `WeaponSpec` through its own `IWeaponRegistry`.
 */
public data class Loadout(val slots: Map<LoadoutSlot, ItemId>) {

    public fun itemIn(slot: LoadoutSlot): ItemId? = slots[slot]

    /** Returns a copy with [slot] set to [itemId], or removed if null. */
    public fun with(slot: LoadoutSlot, itemId: ItemId?): Loadout {
        val next = slots.toMutableMap()
        if (itemId == null) next.remove(slot) else next[slot] = itemId
        return copy(slots = next)
    }

    public companion object {
        public val EMPTY: Loadout = Loadout(emptyMap())
    }
}
