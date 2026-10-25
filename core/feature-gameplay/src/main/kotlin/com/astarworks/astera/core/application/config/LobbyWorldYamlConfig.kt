package com.astarworks.astera.core.application.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `content/worlds/lobby.yaml` (ADR-0180): the procedural sky-island lobby.
 *
 * ```yaml
 * id: lobby
 * seed: 20261022
 * surface_y: 64
 * center: { x: 0, z: 0 }                       # fountain / plaza centre
 * spawn: { x: 0.5, y: 65.0, z: 6.5, yaw: 180.0 }
 * marker_version: 1                            # bump to repaste the build
 * rules: { time_of_day: 6000, pvp_enabled: false, build_enabled: false }
 * ```
 */
@Serializable
public data class LobbyWorldYamlConfig(
    val id: String = DEFAULT_ID,
    val seed: Long,
    @SerialName("surface_y") val surfaceY: Int = 64,
    val center: LobbyCenterYaml = LobbyCenterYaml(),
    val spawn: LobbySpawnYaml,
    @SerialName("marker_version") val markerVersion: Int = 1,
    val rules: LobbyRulesYaml = LobbyRulesYaml(),
) {
    public companion object {
        public const val DEFAULT_ID: String = "lobby"
    }
}

@Serializable
public data class LobbyCenterYaml(val x: Int = 0, val z: Int = 0)

@Serializable
public data class LobbySpawnYaml(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
)

/** World rules the lobby enforces; every flag defaults to the safe-lobby value. */
@Serializable
public data class LobbyRulesYaml(
    @SerialName("time_of_day") val timeOfDay: Long = 6000L,
    @SerialName("time_frozen") val timeFrozen: Boolean = true,
    @SerialName("weather_clear_fixed") val weatherClearFixed: Boolean = true,
    @SerialName("pvp_enabled") val pvpEnabled: Boolean = false,
    @SerialName("build_enabled") val buildEnabled: Boolean = false,
    @SerialName("mob_spawning") val mobSpawning: Boolean = false,
    val hunger: Boolean = false,
    @SerialName("fall_damage") val fallDamage: Boolean = false,
    /** Players below this Y are teleported back to the spawn. */
    @SerialName("void_rescue_y") val voidRescueY: Int = 0,
)
