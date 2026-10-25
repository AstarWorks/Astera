package com.astarworks.astera.core.application.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `content/world-rules/baseline.yaml` (ADR-0245): rules Astera enforces on every loaded world.
 * The defaults are the safe PvP values, so a missing file still yields a mob-free, fixed-daytime server.
 */
@Serializable
public data class WorldBaselineYamlConfig(
    val id: String = "baseline",
    @SerialName("mob_spawning") val mobSpawning: Boolean = false,
    @SerialName("time_of_day") val timeOfDay: Long = 6000L,
    @SerialName("time_frozen") val timeFrozen: Boolean = true,
)
