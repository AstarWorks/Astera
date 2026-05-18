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
 * Design (Wave 5a, worker α):
 *  - Per-slot `damageReduction` values are summed additively, then any active
 *    set bonus (2-piece / 4-piece) is added on top.
 *  - The summed reduction is **capped at 80%** so even maxed-out defenders
 *    can never become invulnerable. The cap is the single multiplicative step
 *    applied to `WorkingDamage`.
 *  - Mitigations are still recorded per-source (one entry per equipped slot
 *    plus one per active set bonus). The total reduction is attributed
 *    proportionally so the sum of `mitigations[].amountReduced` equals the
 *    actual `before - after` damage delta — never more, never less.
 *  - Set bonus is determined by counting pieces from the same [ArmorSetKey].
 *    4 pieces → [com.astarworks.astera.combat.domain.armor.ArmorSet.fourPieceBonus];
 *    2 or 3 pieces → `twoPieceBonus`. Each active set contributes one
 *    `ArmorSet` mitigation entry. Unknown sets (registry returns null) are
 *    silently ignored.
 *
 * Constructor takes [IArmorRegistry] for set lookup; pure on its inputs.
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

        // Per-slot raw reductions (deterministic iteration order).
        val perSlot = mutableListOf<Pair<ArmorSlot, Double>>()
        var totalReduction = 0.0
        for ((slot, spec) in armor.equipped) {
            perSlot += slot to spec.damageReduction
            totalReduction += spec.damageReduction
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
                MitigationApplied(MitigationReason.Armor(slot, percent), share),
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
