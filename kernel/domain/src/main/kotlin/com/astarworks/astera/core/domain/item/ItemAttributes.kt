package com.astarworks.astera.core.domain.item

/**
 * Numeric attribute deltas applied while an item is equipped (Wave _items-1,
 * ADR-0073).
 *
 * Every field is optional — `null` means "leave the wearer's attribute
 * untouched". A non-null value is applied as a *bonus / multiplier* on top
 * of the wearer's base; the exact application (Bukkit Attribute /
 * AttributeModifier) happens in the adapter layer.
 *
 * Range invariants live in `init` so an out-of-range YAML value surfaces at
 * load time (ContentLoader fail-soft logs + skips). [knockbackResistance] is
 * a 0..1 fraction (Bukkit's native domain). [movementSpeedMultiplier] is a
 * non-negative multiplier (>= 0; 1.0 = no change).
 */
public data class ItemAttributes(
    val attackDamage: Double? = null,
    val attackSpeed: Double? = null,
    val armor: Double? = null,
    val armorToughness: Double? = null,
    val knockbackResistance: Double? = null,
    val movementSpeedMultiplier: Double? = null,
    val maxHealthBonus: Double? = null,
) {
    init {
        knockbackResistance?.let {
            require(it in 0.0..1.0) {
                "ItemAttributes.knockbackResistance must be in 0..1, got $it"
            }
        }
        movementSpeedMultiplier?.let {
            require(it >= 0.0) {
                "ItemAttributes.movementSpeedMultiplier must be >= 0, got $it"
            }
        }
    }

    public companion object {
        public val EMPTY: ItemAttributes = ItemAttributes()
    }
}
