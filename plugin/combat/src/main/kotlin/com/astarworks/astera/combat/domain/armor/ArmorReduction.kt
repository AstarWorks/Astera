package com.astarworks.astera.combat.domain.armor

import com.astarworks.astera.core.domain.damage.DamageAttribute

/**
 * Per-attribute damage reduction for a piece of armor.
 *
 * Wave 5a-fix introduces this so a single armor piece can resist different
 * [DamageAttribute]s by different amounts (e.g. fire-resistant chestplate
 * blocks 70% fire but only 25% physical). The pre-fix flat-Double form is
 * preserved via [flat] — equivalent to `default` reduction with no
 * per-attribute entries.
 *
 * Reduction values are in `[0.0, 1.0]` where `0.0` = no resistance and
 * `1.0` = full immunity to that attribute (the global cap of 80% in
 * `ArmorReductionModifier` still applies across all stacked pieces).
 *
 * Per ADR-0031, armor is **not** the long-term home for resist data —
 * Wave 5e migrates per-attribute resists into the universal attribute
 * container as `resist.fire` / `resist.physical` etc. keys. Until then this
 * value type is the canonical way armor expresses defensive intent.
 */
public data class ArmorReduction(
    /** Fallback reduction applied to any attribute not listed in [perAttribute]. */
    val default: Double,
    /** Attribute-specific overrides. Empty map = `default` applies to every attribute. */
    val perAttribute: Map<DamageAttribute, Double> = emptyMap(),
) {
    init {
        require(default in 0.0..1.0) {
            "ArmorReduction.default must be in [0.0, 1.0]: $default"
        }
        perAttribute.forEach { (attr, v) ->
            require(v in 0.0..1.0) {
                "ArmorReduction.perAttribute[$attr] must be in [0.0, 1.0]: $v"
            }
        }
    }

    /** Reduction applied to damage of the given [attr]. */
    public fun forAttribute(attr: DamageAttribute): Double = perAttribute[attr] ?: default

    public companion object {
        /** Flat reduction across every attribute. Matches the pre-Wave-5a-fix semantics. */
        public fun flat(value: Double): ArmorReduction = ArmorReduction(default = value)

        /** Zero reduction — useful as a default / empty value. */
        public val ZERO: ArmorReduction = ArmorReduction(default = 0.0)
    }
}
