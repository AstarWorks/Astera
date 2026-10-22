package com.astarworks.astera.stats.application.eventbus.listeners.progression

import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.domain.progression.SkillKind
import com.astarworks.astera.stats.application.usecase.progression.AwardSkillXpUseCase

/**
 * Awards [SkillKind.Combat] XP whenever a player lands PvP damage
 * (Wave 5multi-skill, ADR-0101).
 *
 * # XP formula
 *
 * `xp = ceil(finalAmount) * COMBAT_XP_PER_DAMAGE` — direct proportionality
 * to damage dealt. Self-damage (attacker == target) and unattributed damage
 * (`attacker == null`, e.g. environmental falls) are skipped.
 *
 * The constant lives in this class as a Phase 1 tuning lever; if the
 * `content/balance/` story matures into a per-axis XP-rate YAML, replace the
 * constant with a registry lookup.
 */
public class AwardCombatXpOnDamageDealtListener(
    private val awardXp: AwardSkillXpUseCase,
    private val xpPerDamage: Long = COMBAT_XP_PER_DAMAGE,
) {
    public fun handle(event: DamageDealt) {
        if (!event.context.countsForProgression) return // ADR-0235: no Combat XP for sandbag hits
        val attacker = event.attacker ?: return
        if (attacker == event.target) return
        val damage = event.resolved.finalAmount
        if (damage <= 0.0) return
        val xp = (Math.ceil(damage).toLong()) * xpPerDamage
        if (xp <= 0L) return
        awardXp.execute(attacker, SkillKind.Combat, xp)
    }

    public companion object {
        public const val COMBAT_XP_PER_DAMAGE: Long = 1L
    }
}
