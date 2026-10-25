package com.astarworks.astera.core.domain.damage

/**
 * Damage attribute / element family.
 *
 * Vendor-neutral and feature-neutral: both `:plugin:weapons` (damage profile
 * on a weapon spec) and `:plugin:combat` (element-vs-element multipliers,
 * resolved damage outputs) need this concept, and per ADR-0022 neither
 * feature plugin may depend on the other. Core hosts the enum so they each
 * reference Core only.
 *
 * Phase 2+ adds rock-paper-scissors interactions (Stage 1 F element matrix
 * lives in the Combat plugin once Phase 4 splits it out; the matrix consumes
 * this enum but does not own it).
 */
public enum class DamageAttribute {
    PHYSICAL,
    FIRE,
    ELECTRIC,
    WIND,
    WATER,
    EARTH,
}
