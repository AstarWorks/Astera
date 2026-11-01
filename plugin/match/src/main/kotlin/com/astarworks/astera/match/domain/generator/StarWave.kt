package com.astarworks.astera.match.domain.generator

import kotlin.time.Duration

/**
 * Wave mode of a [StarGenerator] (`wave:` in `content/generators/`): instead of popping on its period the generator
 * announces a wave [warn] ahead, then scatters the wave's stars (`amount` x the star supply multiplier) one by one over
 * a disc of [scatterRadius] blocks around itself, spread across [scatterDuration]. The first wave lands one [period]
 * after the match went active, or - with [opensAtPhase] > 1 in a phase-driven mode - one [warn] after that phase began
 * (the generator idles before it and the star budget moves its share to the open sources).
 */
public data class StarWave(
    val period: Duration,
    val warn: Duration,
    val scatterRadius: Double,
    val scatterDuration: Duration,
    /** Number (1..5) of the Annihilation phase the wave opens at; 1 = from the start. Ignored by modes without a phase clock. */
    val opensAtPhase: Int = 1,
) {
    init {
        require(period.isPositive()) { "wave_period_seconds must be positive" }
        require(!warn.isNegative() && warn < period) { "wave_warn_seconds must be within 0 until the wave period" }
        require(scatterRadius > 0.0) { "scatter_radius must be > 0 (was $scatterRadius)" }
        require(opensAtPhase in 1..5) { "opens_at_phase must be 1..5 (was $opensAtPhase)" }
        require(scatterDuration.isPositive()) { "scatter_seconds must be positive" }
    }
}
