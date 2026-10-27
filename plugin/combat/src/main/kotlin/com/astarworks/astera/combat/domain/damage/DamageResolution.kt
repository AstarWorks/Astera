package com.astarworks.astera.combat.domain.damage

import com.astarworks.astera.core.domain.damage.DamageAttribute

/**
 * Rich return type of `DamageRule.resolve`. Replaces the bare [ResolvedDamage]
 * so downstream code (`ApplyDamageUseCase`) can emit fine-grained sub-events
 * (CriticalHit / KnockbackApplied / DamageMitigated / FriendlyFireBlocked).
 *
 * [resolved] remains the on-the-wire damage payload (cardinality 1) consumed
 * by [com.astarworks.astera.combat.application.port.outbound.IDamageGateway].
 */
public data class DamageResolution(
    val resolved: ResolvedDamage,
    val mitigations: List<MitigationApplied>,
    val knockback: KnockbackPush?,
    val wasCritical: Boolean,
    val criticalMultiplier: Double,
) {
    public companion object {
        /**
         * Build a "blocked" resolution — friendly-fire policy short-circuits the
         * pipeline. `finalAmount` is 0; `wasBlocked` is true.
         */
        public fun blocked(attempt: DamageAttempt): DamageResolution = DamageResolution(
            resolved = ResolvedDamage(
                finalAmount = 0.0,
                attribute = attempt.baseProfile.attribute,
                wasBlocked = true,
                wasCritical = false,
            ),
            mitigations = emptyList(),
            knockback = null,
            wasCritical = false,
            criticalMultiplier = 1.0,
        )

        /** Build a baseline (no mitigation, no crit, no knockback) resolution. */
        public fun of(
            finalAmount: Double,
            attribute: DamageAttribute,
            wasCritical: Boolean = false,
        ): DamageResolution = DamageResolution(
            resolved = ResolvedDamage(finalAmount, attribute, wasBlocked = false, wasCritical),
            mitigations = emptyList(),
            knockback = null,
            wasCritical = wasCritical,
            criticalMultiplier = if (wasCritical) 2.0 else 1.0,
        )
    }
}
