package com.astarworks.astera.domain.model.player

import com.astarworks.astera.domain.model.weapon.WeaponId

/**
 * A player's chosen weapons keyed by [LoadoutSlot]. Immutable; mutation returns
 * a new instance via [with]. Slots not present in [slots] are interpreted as
 * empty.
 */
public data class Loadout(val slots: Map<LoadoutSlot, WeaponId>) {

    public fun weaponIn(slot: LoadoutSlot): WeaponId? = slots[slot]

    /** Returns a copy with [slot] set to [weaponId], or removed if null. */
    public fun with(slot: LoadoutSlot, weaponId: WeaponId?): Loadout {
        val next = slots.toMutableMap()
        if (weaponId == null) next.remove(slot) else next[slot] = weaponId
        return copy(slots = next)
    }

    public companion object {
        public val EMPTY: Loadout = Loadout(emptyMap())
    }
}
