package com.astarworks.astera.core.domain.item

import com.astarworks.astera.core.domain.resource.ResourceType

/**
 * Weapon family of an item, derived from item data (never from an id list): ammo-fed items are
 * [GUN]s, energy-fed items are [SWORD]s (vanilla `*_SWORD` / `*_AXE` blades) or [STAFF]s (any other
 * energy item). Everything else (gear, tiles, consumables) is not a weapon.
 *
 * Match weapon limits (`weapon_limits` in a game mode's `default_config`, see [WeaponLimits]) count
 * carried weapons per category.
 */
public enum class WeaponCategory(public val key: String) {
    GUN("gun"),
    SWORD("sword"),
    STAFF("staff"),
    ;

    public companion object {
        public fun parse(key: String): WeaponCategory? = entries.firstOrNull { it.key == key.trim().lowercase() }

        /** The weapon family of [spec], or `null` when it is not a weapon. */
        public fun of(spec: ItemSpec): WeaponCategory? = when (spec.resourceType) {
            ResourceType.Ammo -> GUN
            ResourceType.Energy -> if (isBlade(spec)) SWORD else STAFF
            else -> null
        }

        private fun isBlade(spec: ItemSpec): Boolean {
            val material = (spec.template as? ItemTemplate.Vanilla)?.material ?: return false
            return material.endsWith("_SWORD") || material.endsWith("_AXE")
        }
    }
}
