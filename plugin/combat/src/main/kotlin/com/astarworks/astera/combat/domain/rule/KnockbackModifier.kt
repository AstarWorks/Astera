package com.astarworks.astera.combat.domain.rule

import com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer
import com.astarworks.astera.combat.domain.armor.ArmorBundle
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.core.domain.attribute.IAttributeContainer
import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * Computes a knockback impulse vector from a [DamageAttempt]'s source and
 * stashes it onto [WorkingDamage.knockback]. `ApplyDamageUseCase` later hands
 * the vector to `IKnockbackGateway.applyImpulse` and publishes
 * `KnockbackApplied`.
 *
 * Wave 5a simplification
 * ----------------------
 * The pipeline does not yet pipe the defender's world position into
 * [DamageRule.resolve], so we cannot compute a true `(defender - attacker)`
 * direction. Instead we use the *attacker* position as a directional proxy:
 * the impulse points *away from the world origin's view of the attacker*,
 * i.e. `(-attackerPosition).normalize() * baseMagnitude`. In a typical
 * PvP duel where attacker and defender are within a few blocks this differs
 * from the true direction by a small angle (function of `attackerPos`'s
 * magnitude vs. their separation) — fine for the Wave 5a placeholder.
 *
 * Wave 6 follow-up
 * ----------------
 * `DamageAttempt` will be extended with a `defenderPosition: Vec3?` field
 * once the BukkitEventAdapter is wired (the Bukkit `Entity#getLocation`
 * lookup belongs in the adapter, not the use case). Direction will then
 * become `(defenderPosition - attackerPosition).normalize() * baseMagnitude`.
 * Update both the formula and the docstring at that point.
 *
 * Source coverage
 * ---------------
 * - [DamageSource.Item] / [DamageSource.Skill] / [DamageSource.Explosion]
 *   produce knockback when an origin position is known.
 * - [DamageSource.Projectile] currently produces no knockback — the impact
 *   position lives on the projectile entity, not the source. Wave 6 will
 *   surface it.
 * - Environment / Fall / StatusEffectTick / Barrier intentionally never
 *   knock back.
 */
public class KnockbackModifier(
    public val baseMagnitude: Double = 0.4,
) : DamageModifier {
    init {
        require(baseMagnitude >= 0.0) {
            "KnockbackModifier.baseMagnitude must be >= 0: $baseMagnitude"
        }
    }

    override fun apply(
        working: WorkingDamage,
        attempt: DamageAttempt,
        armor: ArmorBundle?,
        defenderContainer: IAttributeContainer?,
        randomizer: IDamageRandomizer,
    ): WorkingDamage {
        if (baseMagnitude == 0.0) return working
        val origin = originPositionFor(attempt.source) ?: return working
        // Direction: away from origin, magnitude = baseMagnitude.
        // Vec3.normalize() returns ZERO when origin == ZERO, in which case
        // multiplying by -baseMagnitude is still ZERO — caller treats a
        // zero-vector as "no knockback" implicitly (gateway no-ops).
        val direction = origin.normalize()
        val impulse = direction * (-baseMagnitude)
        working.knockback = impulse
        return working
    }

    private fun originPositionFor(source: DamageSource): Vec3? = when (source) {
        is DamageSource.Item -> source.attackerPosition
        is DamageSource.Skill -> source.casterPosition
        is DamageSource.Explosion -> source.center
        is DamageSource.Projectile,
        is DamageSource.StatusEffectTick,
        is DamageSource.Barrier,
        is DamageSource.Environment,
        -> null
    }
}
