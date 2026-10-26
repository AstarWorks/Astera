package com.astarworks.astera.combat.application.port.outbound

/**
 * Outcome returned by [IDamageGateway.apply] on success.
 *
 * Callers inspect the variant to decide whether to publish [com.astarworks.astera.combat.domain.event.PlayerKilled]
 * in addition to [com.astarworks.astera.combat.domain.event.DamageDealt].
 */
public sealed class DamageOutcome {
    /** Foreign-region hit accepted by the target entity scheduler; no outcome published yet. */
    public data object Scheduled : DamageOutcome()

    /** The target survived; [remainingHp] is their hit-point total after damage. */
    public data class HpReduced(val remainingHp: Double) : DamageOutcome()

    /** The target was killed by this hit. */
    public data object Killed : DamageOutcome()
}
