package com.astarworks.astera.match.domain.star

import kotlin.math.max
import kotlin.math.min

/**
 * The star supply formula (founder 2026-10-29, anti-snowball): stars must grow as the match goes on, so a team that
 * falls behind can still catch up. One shape for EVERY star generator and every Mid wave:
 *
 * ```
 * multiplier(t) = min(maxMultiplier, 1 + growth * t / rampSeconds)      t = seconds since the match went active
 * stars of one pop / wave = round(base amount * multiplier(t))           (at least 1)
 * ```
 *
 * [rampSeconds] is the expected match length: at `t = rampSeconds` the supply is `1 + growth` times the base; it keeps
 * growing in overtime until it hits [maxMultiplier]. Authored in `content/norma-layer/layer.yaml` (`star_supply_*`),
 * overridable per game mode like every layer key; `docs/game-design/stars-and-norma.md` "Pace" holds the measured table.
 */
public data class StarSupply(
    val growth: Double = DEFAULT_GROWTH,
    val rampSeconds: Double = DEFAULT_RAMP_SECONDS,
    val maxMultiplier: Double = DEFAULT_MAX_MULTIPLIER,
) {
    init {
        require(growth >= 0.0) { "star_supply_growth must be >= 0 (was $growth)" }
        require(rampSeconds > 0.0) { "star_supply_ramp_seconds must be > 0 (was $rampSeconds)" }
        require(maxMultiplier >= 1.0) { "star_supply_max_multiplier must be >= 1 (was $maxMultiplier)" }
    }

    /** Supply multiplier [elapsedSeconds] after the match went active. */
    public fun multiplierAt(elapsedSeconds: Double): Double =
        min(maxMultiplier, 1.0 + growth * max(0.0, elapsedSeconds) / rampSeconds)

    /** Stars of a pop / wave whose authored size is [baseAmount], [elapsedSeconds] into the match. */
    public fun amountAt(baseAmount: Int, elapsedSeconds: Double): Int =
        Math.round(baseAmount * multiplierAt(elapsedSeconds)).toInt().coerceAtLeast(1)

    public companion object {
        public const val DEFAULT_GROWTH: Double = 1.5
        public const val DEFAULT_RAMP_SECONDS: Double = 600.0
        public const val DEFAULT_MAX_MULTIPLIER: Double = 2.5

        /** Reads the `star_supply_*` keys of the merged layer + mode config; a missing key keeps its default, a malformed one fails loudly. */
        public fun parse(raw: Map<String, String>): StarSupply {
            fun number(key: String, fallback: Double): Double =
                raw[key]?.trim()?.let { it.toDoubleOrNull() ?: throw IllegalArgumentException("$key must be a number (was '$it')") } ?: fallback
            return StarSupply(
                growth = number("star_supply_growth", DEFAULT_GROWTH),
                rampSeconds = number("star_supply_ramp_seconds", DEFAULT_RAMP_SECONDS),
                maxMultiplier = number("star_supply_max_multiplier", DEFAULT_MAX_MULTIPLIER),
            )
        }
    }
}
