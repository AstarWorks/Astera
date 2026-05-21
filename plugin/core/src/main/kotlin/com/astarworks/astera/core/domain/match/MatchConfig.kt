package com.astarworks.astera.core.domain.match

import com.astarworks.astera.core.domain.world.WeatherState
import com.astarworks.astera.core.domain.world.WorldDifficulty

/**
 * Per-match world-state overlay. Applied at match start by
 * [com.astarworks.astera.core.application.port.outbound.IMatchConfigService.apply]
 * and reset on match end.
 *
 * Each field is optional:
 *
 * - Non-null fields *override* the underlying world's vanilla state for the
 *   duration of the match.
 * - `null` values leave the world's existing setting untouched (the match
 *   does not care about that axis).
 *
 * Wave 5n (ADR-0080) — bridges the Wave 5c match mode framework with the
 * world-environment ports introduced in this wave.
 *
 * **Immutable**: All fields are `val` (data-class). Konsist test
 * `MatchConfig must be data class without mutable fields` enforces this so
 * downstream use cases can rely on referential transparency.
 */
public data class MatchConfig(
    /** When non-null, locks PvP world-wide for the match duration. */
    val pvpEnabled: Boolean? = true,

    /** When non-null, freezes weather to this state. */
    val weatherMode: WeatherState? = null,

    /** When non-null, freezes time-of-day to this tick value (`0..23999`). */
    val timeMode: Long? = null,

    /** When non-null, overrides world difficulty. */
    val difficulty: WorldDifficulty? = null,

    /** Multiplicative scalar applied to damage in this match. `1.0` = vanilla. */
    val damageMultiplier: Double = 1.0,

    /** When `true`, players keep inventory on death. */
    val keepInventoryOnDeath: Boolean = false,

    /** When `false`, the match's region permissions deny pickup. */
    val pickupAllowed: Boolean = true,

    /** When `false`, the match's region permissions deny drop. */
    val dropAllowed: Boolean = true,
) {
    init {
        require(damageMultiplier >= 0.0) { "damageMultiplier must be >= 0.0 (got $damageMultiplier)" }
        if (timeMode != null) {
            require(timeMode in 0..23999) { "timeMode must be in [0, 23999] (got $timeMode)" }
        }
    }

    public companion object {
        /** Default config — vanilla everywhere; the empty overlay. */
        public val DEFAULT: MatchConfig = MatchConfig()
    }
}
