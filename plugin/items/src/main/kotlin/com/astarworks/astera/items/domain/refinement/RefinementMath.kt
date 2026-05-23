package com.astarworks.astera.items.domain.refinement

import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeModifier

/**
 * Pure-domain coefficient table for [Refinement] → [AttributeModifier]
 * conversion (Wave 5refine, ADR-0100 §Decision "Layer composition /
 * multiplication math").
 *
 * Encodes the HSB 8-layer multiplicative shape: each refinement layer
 * contributes one [AttributeModifier.Operation.MULTIPLY] entry. Composition
 * across layers is handled by [com.astarworks.astera.core.domain.attribute.IAttributeContainer.effectiveNumber]
 * — `MULTIPLY` modifiers compound in registration order (ADR-0058 §1).
 *
 * # Why a table here and not a sealed-method
 *
 * The coefficients are *balance numbers*, not behaviour. Keeping them in one
 * lookup table makes them grep-able (`grep "0.10"` finds every magic
 * constant) and lets balance review touch one file instead of five sealed
 * branches. The dispatch is a single `when` — Kotlin enforces exhaustiveness
 * the same way.
 *
 * # Default target stat
 *
 * Phase 1 stays simple: every layer hits a single shared "refined power"
 * key (`item.refined_power_multiplier`). Combat reads this once at hit-time
 * via `container.effectiveNumber(REFINED_POWER_KEY)` and multiplies its
 * resolved damage. Per-stat targeted gemstones can land via a follow-up ADR
 * — the math primitive does not need to change, only the key per branch.
 */
public object RefinementMath {

    /** Single shared key Phase 1 layers contribute to. */
    public val REFINED_POWER_KEY: AttributeKey = AttributeKey("item.refined_power_multiplier")

    /** Triple (key, op, value) tuple consumed by `ItemInstance.rebuildContainer()`. */
    public data class ModifierComponents(
        val key: AttributeKey,
        val op: AttributeModifier.Operation,
        val value: Double,
    )

    /**
     * Produce the (key, op, value) components for a single [layer].
     *
     * Coefficients (Phase 1, tunable per ADR-0100):
     *   - Reforge:  +10% per tier
     *   - Enchant:  +5%  per level
     *   - Gemstone: +tier*8% (slotIndex ignored at math layer, used only for storage uniqueness)
     *   - Star:     +8% per star
     *   - Recomb:   +score% (1 score = 1%)
     */
    public fun modifierFor(layer: Refinement): ModifierComponents {
        val multiplier: Double = when (layer) {
            is Refinement.Reforge -> 1.0 + layer.tier * 0.10
            is Refinement.Enchant -> 1.0 + layer.level * 0.05
            is Refinement.Gemstone -> 1.0 + layer.tier * 0.08
            is Refinement.Star -> 1.0 + layer.stars * 0.08
            is Refinement.Recomb -> 1.0 + layer.score / 100.0
        }
        return ModifierComponents(
            key = REFINED_POWER_KEY,
            op = AttributeModifier.Operation.MULTIPLY,
            value = multiplier,
        )
    }

    /**
     * Escalating reroll cost (Warframe Riven pattern from ADR-0100
     * §"Resource sink", capped at 5 then plateau):
     *
     *   roll 1 → 900   roll 2 → 1000   roll 3 → 2000   roll 4 → 2500
     *   roll 5+ → 3000
     */
    public fun rerollCost(rerollIndex: Int): Long {
        require(rerollIndex >= 0) { "rerollIndex must be >= 0, got $rerollIndex" }
        return when (rerollIndex) {
            0 -> 900L
            1 -> 1000L
            2 -> 2000L
            3 -> 2500L
            else -> 3000L
        }
    }
}
