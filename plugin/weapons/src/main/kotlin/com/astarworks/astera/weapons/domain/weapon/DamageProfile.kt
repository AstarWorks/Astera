package com.astarworks.astera.weapons.domain.weapon

import com.astarworks.astera.core.domain.damage.DamageAttribute

/**
 * Pure damage descriptor. Combat math lives in `WeaponDamageRule` (currently
 * legacy `:plugin:domain.rule.WeaponDamageRule`, Phase 4 moves it into
 * `:plugin:combat`), not on this value type, so the data stays
 * serializable and side-effect-free.
 *
 * ADR-0022 Phase 3: [DamageAttribute] lives in `:plugin:core` (vendor-neutral,
 * referenced by both Weapons and Combat); only [DamageProfile] is
 * Weapons-owned.
 */
public data class DamageProfile(
    val base: Double,
    val attribute: DamageAttribute,
) {
    init {
        require(base >= 0.0) { "DamageProfile.base must be >= 0, got $base" }
    }
}
