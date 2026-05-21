package com.astarworks.astera.core.domain.resource

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Per-player snapshot of Ammo / Stamina / Mana (Wave 5k, ADR-0080).
 *
 * Plain data, immutable. The active resource shown on the HUD is selected
 * by [activeWeapon] — when the player swaps items the HUD picks up the new
 * archetype's resource type via
 * [com.astarworks.astera.core.domain.item.ItemSpec.resourceType].
 *
 * Three independent counters are stored unconditionally; the active one is
 * just the one the HUD renders. This avoids "what value do I read when the
 * player swaps weapons mid-cast?" by always tracking all three.
 */
public data class PlayerResourcePool(
    val playerId: PlayerId,
    val activeWeapon: ItemId?,
    val ammo: Int,
    val stamina: Int,
    val mana: Int,
) {
    init {
        require(ammo >= 0) { "ammo must be >= 0, got $ammo" }
        require(stamina >= 0) { "stamina must be >= 0, got $stamina" }
        require(mana >= 0) { "mana must be >= 0, got $mana" }
    }

    /** Read the value for [type] from this snapshot. */
    public fun valueOf(type: ResourceType): Int = when (type) {
        ResourceType.Ammo -> ammo
        ResourceType.Stamina -> stamina
        ResourceType.Mana -> mana
    }

    /** Return a copy with [type] set to [value] (clamped to >= 0). */
    public fun with(type: ResourceType, value: Int): PlayerResourcePool {
        val v = value.coerceAtLeast(0)
        return when (type) {
            ResourceType.Ammo -> copy(ammo = v)
            ResourceType.Stamina -> copy(stamina = v)
            ResourceType.Mana -> copy(mana = v)
        }
    }
}
