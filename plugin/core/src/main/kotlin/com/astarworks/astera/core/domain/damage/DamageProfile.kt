package com.astarworks.astera.core.domain.damage

/**
 * Pure damage descriptor. Combat math lives in `DamageRule` (pluggable modifier
 * pipeline in `:plugin:combat`, see ADR-0030); this is just the
 * serializable, side-effect-free input.
 *
 * Promoted to `:plugin:core` in Wave _items-1 (ADR-0073): every feature
 * plugin (Items, Weapons, Combat) needs to reference the profile from inline
 * item specs, so it must live in core. [DamageAttribute] was already here.
 */
public data class DamageProfile(
    val base: Double,
    val attribute: DamageAttribute,
) {
    init {
        require(base >= 0.0) { "DamageProfile.base must be >= 0, got $base" }
    }
}
