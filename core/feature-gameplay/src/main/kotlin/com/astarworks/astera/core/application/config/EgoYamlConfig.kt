package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.domain.ego.EgoConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO for `content/balance/ego.yaml` (ADR-0231). Every field is optional and
 * falls back to [EgoConfig.DEFAULTS].
 *
 * ```yaml
 * max: 100
 * gain_on_dealt: 5          # per hit landed
 * gain_on_taken: 4          # per hit taken
 * decay_per_second: 2       # out of combat; 0 = never decays
 * decay_delay_ticks: 200    # out-of-combat time before decay starts
 * ```
 */
@Serializable
public data class EgoYamlConfig(
    val max: Int = EgoConfig.DEFAULTS.max,
    @SerialName("gain_on_dealt") val gainOnDealt: Int = EgoConfig.DEFAULTS.gainOnDealt,
    @SerialName("gain_on_taken") val gainOnTaken: Int = EgoConfig.DEFAULTS.gainOnTaken,
    @SerialName("decay_per_second") val decayPerSecond: Int = EgoConfig.DEFAULTS.decayPerSecond,
    @SerialName("decay_delay_ticks") val decayDelayTicks: Int = EgoConfig.DEFAULTS.decayDelayTicks,
) {
    /** @throws IllegalArgumentException on out-of-range values (the loader logs it and keeps defaults). */
    public fun toDomain(): EgoConfig = EgoConfig(max, gainOnDealt, gainOnTaken, decayPerSecond, decayDelayTicks)
}
