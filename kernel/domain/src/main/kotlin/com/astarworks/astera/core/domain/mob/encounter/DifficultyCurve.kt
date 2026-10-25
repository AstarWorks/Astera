package com.astarworks.astera.core.domain.mob.encounter

import kotlin.math.pow

/**
 * Per-zone difficulty scaling curve (Wave 5pve-boss, ADR-0098).
 *
 * Encounter zones scale enemy stats (HP, damage) with the player's level.
 * The curve is pure-domain math so the spawn ladder + boss lifecycle
 * service can apply the same scaling deterministically at both spawn and
 * (later) damage-resolution time.
 *
 * Phase 1 ships **one** curve kind — `Polynomial` (`base * (1 + k * lvl^p)`)
 * — because the OSRS / HSB style scaling fits a polynomial cleanly and
 * does not need conditional rules. Sealed so future kinds (`Stepped`,
 * `LookupTable`) can land via a supersede ADR.
 *
 * `level` is the **effective encounter level** (per-player or party-average,
 * decided by the spawn ladder). The curve doesn't know whose level it is —
 * caller passes the value.
 */
public sealed interface DifficultyCurve {

    /** Compute the scaled value of [base] at the given encounter [level]. */
    public fun scale(base: Double, level: Int): Double

    /** Constant — no scaling (useful for tutorial zones). */
    public data object Flat : DifficultyCurve {
        override fun scale(base: Double, level: Int): Double = base
    }

    /**
     * `base * (1 + slope * (level - pivot)^exponent)` clamped to >= 0.
     *
     * Phase 1 convention: `pivot = 1`, `exponent = 1.0` produces a linear
     * curve (`base * (1 + slope * (level - 1))`) which is the default
     * Annihilation scaling. `exponent = 1.5` produces a mild super-linear
     * curve suitable for raid zones.
     */
    public data class Polynomial(
        public val slope: Double,
        public val pivot: Int = 1,
        public val exponent: Double = 1.0,
    ) : DifficultyCurve {
        init {
            require(slope.isFinite()) { "Polynomial.slope must be finite: $slope" }
            require(slope >= 0.0) { "Polynomial.slope must be >= 0: $slope" }
            require(pivot >= 1) { "Polynomial.pivot must be >= 1: $pivot" }
            require(exponent.isFinite() && exponent > 0.0) {
                "Polynomial.exponent must be > 0 and finite: $exponent"
            }
        }

        override fun scale(base: Double, level: Int): Double {
            val delta = (level - pivot).toDouble()
            if (delta <= 0.0) return base
            val factor = 1.0 + slope * delta.pow(exponent)
            return (base * factor).coerceAtLeast(0.0)
        }
    }
}
