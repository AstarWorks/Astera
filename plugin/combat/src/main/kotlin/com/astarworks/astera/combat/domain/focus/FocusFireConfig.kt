package com.astarworks.astera.combat.domain.focus

/**
 * Tuning knobs for focus-fire detection (Wave 5combat-depth, ADR-0105).
 *
 * Focus-fire activates when [minAttackers] distinct players damage the
 * same victim within [windowTicks]. Once active, the [bonusMultiplier]
 * applies to every subsequent damage step from those attackers against
 * the same victim until [windowTicks] of inactivity expire the focus.
 *
 * # Defaults
 *
 * - `minAttackers = 3` — matches Apex/Valorant "stack damage" convention
 *   (a duo doesn't count as focus; a trio does).
 * - `windowTicks = 100` (5 s) — long enough that thoughtful team play
 *   builds focus, short enough that a single late hit doesn't extend it
 *   indefinitely.
 * - `bonusMultiplier = 1.15` — modest +15%; competitive integrity comes
 *   first. Raising this further is a balance ADR, not a runtime knob.
 */
public data class FocusFireConfig(
    public val minAttackers: Int = DEFAULT_MIN_ATTACKERS,
    public val windowTicks: Int = DEFAULT_WINDOW_TICKS,
    public val bonusMultiplier: Double = DEFAULT_BONUS_MULTIPLIER,
) {
    init {
        require(minAttackers >= 2) {
            "FocusFireConfig.minAttackers must be >= 2 (1 attacker is not a focus): $minAttackers"
        }
        require(windowTicks > 0) {
            "FocusFireConfig.windowTicks must be > 0: $windowTicks"
        }
        require(bonusMultiplier.isFinite()) {
            "FocusFireConfig.bonusMultiplier must be finite: $bonusMultiplier"
        }
        require(bonusMultiplier in 1.0..MAX_BONUS_MULTIPLIER) {
            "FocusFireConfig.bonusMultiplier must be in [1.0, $MAX_BONUS_MULTIPLIER]: $bonusMultiplier"
        }
    }

    public companion object {
        public const val DEFAULT_MIN_ATTACKERS: Int = 3
        public const val DEFAULT_WINDOW_TICKS: Int = 100
        public const val DEFAULT_BONUS_MULTIPLIER: Double = 1.15

        /** Cap on bonusMultiplier to preserve competitive integrity. */
        public const val MAX_BONUS_MULTIPLIER: Double = 2.0

        public val DEFAULT: FocusFireConfig = FocusFireConfig()
    }
}
