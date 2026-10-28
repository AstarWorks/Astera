package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.domain.player.physiology.HealthRegenConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO for `content/balance/health-regen.yaml`. Unknown keys and out-of-range values fail loudly.
 *
 * ```yaml
 * regen_per_second: 1.0
 * out_of_combat_delay_ticks: 100
 * ```
 */
@Serializable
public data class HealthRegenYamlConfig(
    @SerialName("regen_per_second") val regenPerSecond: Double = HealthRegenConfig.DEFAULTS.regenPerSecond,
    @SerialName("out_of_combat_delay_ticks") val outOfCombatDelayTicks: Int = HealthRegenConfig.DEFAULTS.outOfCombatDelayTicks,
) {
    /** @throws IllegalArgumentException on out-of-range values. */
    public fun toDomain(): HealthRegenConfig = HealthRegenConfig(regenPerSecond, outOfCombatDelayTicks)
}
