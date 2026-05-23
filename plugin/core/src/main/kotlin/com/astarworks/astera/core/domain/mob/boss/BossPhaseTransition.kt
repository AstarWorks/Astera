package com.astarworks.astera.core.domain.mob.boss

/**
 * Trigger that promotes a boss from one [BossPhase] to the next (Wave 5pve-boss,
 * ADR-0098).
 *
 * Phase 1 ships **one** transition kind — HP threshold — because the
 * Annihilation PoC ("Scorched Titan" / "Abyssal Lich") only needs phase
 * promotion when boss HP drops below a percentage. Additional kinds
 * (mechanic-driven, time-driven) land via a sealed expansion + supersede
 * ADR rather than widening this record. The sealed interface is the
 * extension point.
 *
 * Evaluation contract: the lifecycle service evaluates transitions in the
 * order they appear in [BossSpec.transitions]. The first matching transition
 * fires and the boss moves to [toPhase]. Transitions never roll back —
 * once a phase is entered, the next transition's `fromPhase` must be > the
 * current phase, otherwise the spec is rejected at registration time.
 */
public sealed interface BossPhaseTransition {

    /** Phase the boss must currently be in for this transition to be eligible. */
    public val fromPhase: Int

    /** Phase the boss enters when this transition fires. Must be > [fromPhase]. */
    public val toPhase: Int

    /**
     * HP-percentage-driven transition. Fires the first server tick on which
     * `(currentHp / maxHp) <= hpFraction` while the boss is in [fromPhase].
     *
     * Phase 1 PoC convention: thresholds are listed in descending order
     * (`0.66 → 0.33`) so the natural ordering of [BossSpec.transitions] is
     * also the firing order as the boss takes damage.
     */
    public data class HpThreshold(
        public override val fromPhase: Int,
        public override val toPhase: Int,
        public val hpFraction: Double,
    ) : BossPhaseTransition {
        init {
            require(fromPhase >= 0) { "HpThreshold.fromPhase must be >= 0: $fromPhase" }
            require(toPhase > fromPhase) {
                "HpThreshold: toPhase ($toPhase) must be > fromPhase ($fromPhase)"
            }
            require(hpFraction in 0.0..1.0) {
                "HpThreshold.hpFraction must be in [0.0, 1.0]: $hpFraction"
            }
        }
    }
}
