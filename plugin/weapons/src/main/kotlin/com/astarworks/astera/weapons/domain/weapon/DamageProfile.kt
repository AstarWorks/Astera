package com.astarworks.astera.weapons.domain.weapon

import com.astarworks.astera.core.domain.damage.DamageAttribute

/**
 * Pure damage descriptor. Combat math lives in `DamageRule` (pluggable modifier
 * pipeline in `:plugin:combat`, see ADR-0030); this is just the
 * serializable, side-effect-free input.
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
