package com.astarworks.astera.core.domain.resource

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Per-player snapshot of Ammo / Energy (Wave 5k, ADR-0080; ADR-0194).
 *
 * Plain data, immutable. The active resource shown on the HUD is selected
 * by [activeWeapon] — when the player swaps items the HUD picks up the new
 * archetype's resource type via
 * [com.astarworks.astera.core.domain.item.ItemSpec.resourceType].
 *
 * Both counters are stored unconditionally; swapping weapons never changes
 * what is stored, only which spec's cap applies.
 */
public data class PlayerResourcePool(
    val playerId: PlayerId,
    val activeWeapon: ItemId?,
    val ammo: Int,
    val energy: Int,
) {
    init {
        require(ammo >= 0) { "ammo must be >= 0, got $ammo" }
        require(energy >= 0) { "energy must be >= 0, got $energy" }
    }

    /** Read the value for [type] from this snapshot. */
    public fun valueOf(type: ResourceType): Int = when (type) {
        ResourceType.Ammo -> ammo
        ResourceType.Energy -> energy
    }

    /** Return a copy with [type] set to [value] (clamped to >= 0). */
    public fun with(type: ResourceType, value: Int): PlayerResourcePool {
        val v = value.coerceAtLeast(0)
        return when (type) {
            ResourceType.Ammo -> copy(ammo = v)
            ResourceType.Energy -> copy(energy = v)
        }
    }
}
