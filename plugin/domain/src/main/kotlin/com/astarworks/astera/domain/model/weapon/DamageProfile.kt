package com.astarworks.astera.domain.model.weapon

/** Damage attribute / element. Phase 2+ adds rock-paper-scissors interactions. */
enum class DamageAttribute {
    PHYSICAL,
    FIRE,
    ELECTRIC,
    WIND,
    WATER,
    EARTH,
}

/**
 * Pure damage descriptor. Combat math lives in [com.astarworks.astera.domain.rule.WeaponDamageRule],
 * not on this value type, so the data stays serializable and side-effect-free.
 */
data class DamageProfile(
    val base: Double,
    val attribute: DamageAttribute,
) {
    init {
        require(base >= 0.0) { "DamageProfile.base must be >= 0, got $base" }
    }
}
