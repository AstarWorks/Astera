package com.astarworks.astera.combat.domain.rule

import com.astarworks.astera.combat.application.port.outbound.IArmorRegistry
import com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer
import com.astarworks.astera.combat.domain.armor.ArmorBundle
import com.astarworks.astera.combat.domain.armor.ArmorSetKey
import com.astarworks.astera.combat.domain.armor.ArmorSlot
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.MitigationApplied
import com.astarworks.astera.combat.domain.damage.MitigationReason
import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.attribute.IAttributeContainer
import com.astarworks.astera.core.domain.damage.DamageAttribute

/**
 * Aggregates equipped armor reduction (per-slot + set bonuses) and applies it
 * to [WorkingDamage] as a single multiplicative reduction.
 *
 * Wave 5e (ADR-0058 §armor): the per-attribute resist value is now read from
 * the defender's [IAttributeContainer] via
 * `effectiveNumber(Attributes.RESIST_<attribute>)` instead of walking each
 * equipped [com.astarworks.astera.combat.domain.armor.ArmorSpec.reduction]
 * directly. `ApplyArmorModifiersUseCase` is responsible for keeping those
 * resist keys in sync with the equipped bundle (one
 * [com.astarworks.astera.core.domain.attribute.AttributeModifier] per
 * `(slot, attribute)` pair, source-tagged `"armor:<slot>:<armorSpecId>"`).
 *
 * Per-slot attribution is still computed by walking [ArmorBundle.equipped] so
 * the `DamageMitigated` event contract (one entry per equipped slot, each
 * carrying its `MitigationReason.Armor(slot, attribute, percent)`) is
 * preserved. The slot-share of the total container resist is computed
 * proportionally — same shape as Wave 5a-fix, just sourced from the container
 * instead of the spec directly.
 *
 * Design:
 *  - Per-slot reductions for telemetry come from
 *    `spec.reduction.forAttribute(attackAttribute)` (read of static spec, fine
 *    in the hot path because it's a single map lookup — the container value
 *    is the authoritative sum, the spec values are the attribution proxy).
 *  - The total reduction applied is **`defenderContainer.effectiveNumber(RESIST_X)`**
 *    plus any active set bonus (2-piece / 4-piece) on top.
 *  - The summed reduction is **capped at 80%** so even maxed-out defenders
 *    can never become invulnerable. The cap is the single multiplicative
 *    step applied to `WorkingDamage`. Phase C may revisit this cap
 *    location, but Wave 5e leaves it on the modifier (option a) to keep
 *    `ArmorModifier` write-time logic simple — the modifier knows nothing
 *    of other slots, so it can't enforce a sum-cap at write time.
 *  - Set bonus is determined by counting pieces from the same
 *    [ArmorSetKey]. 4 pieces → `ArmorSet.fourPieceBonus`; 2 or 3 pieces →
 *    `twoPieceBonus`. Set bonuses are attribute-agnostic.
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
        defenderContainer: IAttributeContainer?,
        randomizer: IDamageRandomizer,
    ): WorkingDamage {
        if (armor == null || armor.equipped.isEmpty()) return working
        val before = working.currentAmount
        if (before <= 0.0) return working

        val attackAttribute = attempt.baseProfile.attribute
        val resistKey = resistKeyFor(attackAttribute)

        // Authoritative per-slot total reduction: from the defender's container
        // when present (Wave 5e onward); falls back to the spec walk when no
        // container is supplied (tests that bypass ApplyArmorModifiersUseCase,
        // and the small window before LoadoutChanged → ApplyArmorModifiers fires).
        val containerResist: Double = defenderContainer
            ?.effectiveNumber(resistKey)
            ?: armor.equipped.values.sumOf { it.reduction.forAttribute(attackAttribute) }

        // Per-slot attribution remains spec-derived: it's the per-slot share
        // of THIS attribute's reduction that the wearer's static armor list
        // contributes. The sum may not equal `containerResist` exactly
        // (status-effect resist modifiers could be in there too); we
        // attribute proportionally to the slot percentages, so the
        // MitigationReason.Armor entries always sum to the actual delta.
        val perSlot = mutableListOf<Pair<ArmorSlot, Double>>()
        for ((slot, spec) in armor.equipped) {
            perSlot += slot to spec.reduction.forAttribute(attackAttribute)
        }

        // Set bonus aggregation: count pieces per setKey, look up active bonus tier.
        val setCounts = armor.equipped.values
            .mapNotNull { it.setKey }
            .groupingBy { it }
            .eachCount()
        val setBonusEntries = mutableListOf<Pair<ArmorSetKey, Double>>()
        var setBonusTotal = 0.0
        for ((setKey, count) in setCounts) {
            val set = registry.findSet(setKey) ?: continue
            val bonus = when {
                count >= 4 -> set.fourPieceBonus
                count >= 2 -> set.twoPieceBonus
                else -> 0.0
            }
            if (bonus > 0.0) {
                setBonusTotal += bonus
                setBonusEntries += setKey to bonus
            }
        }

        val totalReduction = containerResist + setBonusTotal
        if (totalReduction <= 0.0) return working

        val capped = totalReduction.coerceAtMost(maxReduction)
        working.multiplyBy(1.0 - capped)
        val totalReduced = before - working.currentAmount

        // Proportional attribution. Distribute `totalReduced` first across
        // the slot percentages (sum = containerResist's slot-share), then
        // across set bonuses. If both `containerResist` and the
        // per-slot-sum are zero (status-only resists on container, no armor
        // resist), we skip slot attribution to avoid div-by-zero — the
        // mitigation entries simply omit per-slot detail in that edge case.
        val perSlotSum = perSlot.sumOf { it.second }
        // The fraction of containerResist that came from the static armor walk.
        // When the container has additional non-armor resists, the slots
        // attribute only their share; the rest is "uncredited" container
        // resist (status effects, etc.) and is intentionally not surfaced
        // as a MitigationReason.Armor entry — that would be a misattribution.
        val attributableViaSlots = if (perSlotSum > 0.0) {
            // Slot contributions, in raw reduction units, capped to containerResist.
            // If perSlotSum exceeds containerResist (e.g. a debuff lowered
            // the container value below the spec sum), clamp.
            perSlotSum.coerceAtMost(containerResist)
        } else {
            0.0
        }
        for ((slot, percent) in perSlot) {
            if (percent <= 0.0) continue
            // Each slot's share of the total damage reduced:
            //   share = (slot% / totalReduction) * totalReduced,
            // scaled by the attributable fraction so we don't credit slots
            // for resist that came from outside the armor.
            val scale = if (perSlotSum > 0.0) attributableViaSlots / perSlotSum else 0.0
            val share = (percent * scale / totalReduction) * totalReduced
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

        /**
         * Map [DamageAttribute] → the corresponding `Attributes.RESIST_*`
         * [AttributeKey]. Co-located here (and re-used by
         * `ApplyArmorModifiersUseCase`) so the two sides of the migration
         * read/write the same namespace.
         */
        public fun resistKeyFor(attribute: DamageAttribute): AttributeKey = when (attribute) {
            DamageAttribute.PHYSICAL -> Attributes.RESIST_PHYSICAL
            DamageAttribute.FIRE -> Attributes.RESIST_FIRE
            DamageAttribute.ELECTRIC -> Attributes.RESIST_ELECTRIC
            DamageAttribute.WIND -> Attributes.RESIST_WIND
            DamageAttribute.WATER -> Attributes.RESIST_WATER
            DamageAttribute.EARTH -> Attributes.RESIST_EARTH
        }
    }
}
