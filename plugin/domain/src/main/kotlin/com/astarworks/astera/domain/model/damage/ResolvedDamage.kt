package com.astarworks.astera.domain.model.damage

import com.astarworks.astera.domain.model.weapon.DamageAttribute

/**
 * Outcome of running [com.astarworks.astera.domain.rule.DamageRule] over a
 * [DamageAttempt].
 *
 * `finalAmount` is the post-multiplier damage. `wasBlocked` is reserved for
 * the Phase 3 shield mechanic and stays `false` in Phase 2.
 */
public data class ResolvedDamage(
    val finalAmount: Double,
    val attribute: DamageAttribute,
    val wasBlocked: Boolean,
    val wasCritical: Boolean,
) {
    init {
        require(finalAmount >= 0.0) { "ResolvedDamage.finalAmount must be >= 0: $finalAmount" }
    }
}
