package com.astarworks.astera.match.application.config

import com.astarworks.astera.core.domain.stage.SpawnPoint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * YAML-shaped DTO for a single `spawn_points:` entry inside a stage definition
 * (Wave 4.5 / R3.5, ADR-0027).
 *
 * Polymorphic by the `type:` discriminator — kotlinx-JSON convention; kaml's
 * `PolymorphismStyle.Property` reads the same property so the same DTO works
 * across YAML / TOML / JSON wire formats (ADR-0016). [Vec3YamlConfig] is
 * reused as-is from `StageYamlConfig.kt`; defining a parallel Vec3 here would
 * duplicate a primitive and split the schema arbitrarily.
 *
 * # Wire format
 *
 * ```yaml
 * spawn_points:
 *   - type: attacker
 *     pos: { x: -45.0, y: 64.0, z: 0.0 }
 *   - type: defender
 *     pos: { x:  45.0, y: 64.0, z: 0.0 }
 *   - type: lobby
 *     pos: { x:   0.0, y: 80.0, z: 0.0 }
 *   - type: spectator
 *     pos: { x:   0.0, y: 100.0, z: 0.0 }
 * ```
 *
 * Each variant maps 1:1 onto a [SpawnPoint] sealed subtype. Per the domain
 * contract, facing (yaw / pitch) is deferred to a later wave — the domain
 * [SpawnPoint] only carries a position today.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class SpawnPointYamlConfig {

    public abstract fun toDomain(): SpawnPoint

    @Serializable
    @SerialName("attacker")
    public data class Attacker(val pos: Vec3YamlConfig) : SpawnPointYamlConfig() {
        override fun toDomain(): SpawnPoint = SpawnPoint.Attacker(pos.toVec3())
    }

    @Serializable
    @SerialName("defender")
    public data class Defender(val pos: Vec3YamlConfig) : SpawnPointYamlConfig() {
        override fun toDomain(): SpawnPoint = SpawnPoint.Defender(pos.toVec3())
    }

    @Serializable
    @SerialName("lobby")
    public data class Lobby(val pos: Vec3YamlConfig) : SpawnPointYamlConfig() {
        override fun toDomain(): SpawnPoint = SpawnPoint.Lobby(pos.toVec3())
    }

    @Serializable
    @SerialName("spectator")
    public data class Spectator(val pos: Vec3YamlConfig) : SpawnPointYamlConfig() {
        override fun toDomain(): SpawnPoint = SpawnPoint.Spectator(pos.toVec3())
    }
}
