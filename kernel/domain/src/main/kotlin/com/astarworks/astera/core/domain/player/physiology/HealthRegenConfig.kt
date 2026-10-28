package com.astarworks.astera.core.domain.player.physiology

/**
 * Tuning of the natural HP regen, loaded from `content/balance/health-regen.yaml`.
 *
 * Once a player has neither dealt nor taken a hit for [outOfCombatDelayTicks],
 * their HP pool rises by [regenPerSecond] every second until it is full.
 */
public data class HealthRegenConfig(
    val regenPerSecond: Double = 1.0,
    val outOfCombatDelayTicks: Int = 100,
) {
    init {
        require(regenPerSecond.isFinite() && regenPerSecond >= 0.0) { "regen_per_second must be >= 0, got $regenPerSecond" }
        require(outOfCombatDelayTicks >= 0) { "out_of_combat_delay_ticks must be >= 0, got $outOfCombatDelayTicks" }
    }

    public companion object {
        public val DEFAULTS: HealthRegenConfig = HealthRegenConfig()
    }
}
