package com.astarworks.astera.combat.domain.rule

import com.astarworks.astera.combat.application.port.outbound.IArmorRegistry
import com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer
import com.astarworks.astera.combat.domain.armor.ArmorBundle
import com.astarworks.astera.combat.domain.armor.ArmorSetKey
import com.astarworks.astera.combat.domain.armor.ArmorSlot
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.MitigationApplied
import com.astarworks.astera.combat.domain.damage.MitigationReason

/**
 * Aggregates equipped armor reduction (per-slot + set bonuses) and applies it
 * to [WorkingDamage] as a single multiplicative reduction.
 *
 * Wave 5a-fix: per-slot reduction is now looked up against
 * `attempt.baseProfile.attribute` so a fire-resistant chestplate blocks more
 * fire damage than physical. Each emitted [MitigationReason.Armor] entry
 * carries the attribute it was resolved for.
 *
 * Design:
 *  - Per-slot reductions are summed additively (now per-attribute via
 *    [com.astarworks.astera.combat.domain.armor.ArmorReduction.forAttribute]),
 *    then any active set bonus (2-piece / 4-piece) is added on top.
 *  - The summed reduction is **capped at 80%** so even maxed-out defenders
 *    can never become invulnerable. The cap is the single multiplicative
 *    step applied to `WorkingDamage`.
 *  - Mitigations are recorded per-source. The total reduction is
 *    attributed proportionally so the sum of `mitigations[].amountReduced`
 *    equals the actual `before - after` damage delta — never more, never
 *    less.
 *  - Set bonus is determined by counting pieces from the same
 *    [ArmorSetKey]. 4 pieces → `ArmorSet.fourPieceBonus`; 2 or 3 pieces →
 *    `twoPieceBonus`. Set bonuses are attribute-agnostic in Wave 5a-fix
 *    (the full per-attribute resist story lives in ADR-0031 §1, Wave 5e).
 *  - Unknown sets (registry returns null) are silently ignored.
 */
public class ArmorReductionModifier(
    private val registry: IArmorRegistry,
    private val maxReduction: Double = DEFAULT_MAX_REDUCTION,
) : DamageModifier {
    init {
        require(maxReduction in 0.0..1.0) {
            "ArmorReductionModifier.maxReduction must be in [0.0, 1.0]: $maxReduction"
        }
    }

    override fun apply(
        working: WorkingDamage,
        attempt: DamageAttempt,
        armor: ArmorBundle?,
        randomizer: IDamageRandomizer,
    ): WorkingDamage {
        if (armor == null || armor.equipped.isEmpty()) return working
        val before = working.currentAmount
        if (before <= 0.0) return working

        val attackAttribute = attempt.baseProfile.attribute

        // Per-slot reductions resolved against the attacker's attribute.
        val perSlot = mutableListOf<Pair<ArmorSlot, Double>>()
        var totalReduction = 0.0
        for ((slot, spec) in armor.equipped) {
            val reduction = spec.reduction.forAttribute(attackAttribute)
            perSlot += slot to reduction
            totalReduction += reduction
        }

        // Set bonus aggregation: count pieces per setKey, look up active bonus tier.
        val setCounts = armor.equipped.values
            .mapNotNull { it.setKey }
            .groupingBy { it }
            .eachCount()
        val setBonusEntries = mutableListOf<Pair<ArmorSetKey, Double>>()
        for ((setKey, count) in setCounts) {
            val set = registry.findSet(setKey) ?: continue
            val bonus = when {
                count >= 4 -> set.fourPieceBonus
                count >= 2 -> set.twoPieceBonus
                else -> 0.0
            }
            if (bonus > 0.0) {
                totalReduction += bonus
                setBonusEntries += setKey to bonus
            }
        }

        if (totalReduction <= 0.0) return working

        val capped = totalReduction.coerceAtMost(maxReduction)
        working.multiplyBy(1.0 - capped)
        val totalReduced = before - working.currentAmount

        // Proportional attribution so sum of mitigations equals totalReduced.
        for ((slot, percent) in perSlot) {
            if (percent <= 0.0) continue
            val share = (percent / totalReduction) * totalReduced
            working.recordMitigation(
                MitigationApplied(
                    MitigationReason.Armor(slot, attackAttribute, percent),
                    share,
                ),
            )
        }
        for ((setKey, bonus) in setBonusEntries) {
            val share = (bonus / totalReduction) * totalReduced
            working.recordMitigation(
                MitigationApplied(MitigationReason.ArmorSet(setKey.value, bonus), share),
            )
        }

        return working
    }

    public companion object {
        /**
         * Hard ceiling on total armor-derived reduction (per ADR-0030 design
         * note: armor must never cause invuln). 80% caps a 10 → 2 floor.
         */
        public const val DEFAULT_MAX_REDUCTION: Double = 0.8
    }
}
