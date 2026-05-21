package com.astarworks.astera.match.application.config

import com.astarworks.astera.core.domain.stage.SpawnFaction
import com.astarworks.astera.core.domain.stage.SpawnPoint
import com.astarworks.astera.core.domain.world.WorldId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for a single `spawn_points:` entry inside a stage definition.
 *
 * # Wave 5o (ADR-0081) reshape
 *
 * Pre-Wave-5o the schema discriminated by `type: attacker | defender | …` and
 * carried only `pos`. The new schema is a flat data shape with `faction:`
 * (string-enum), required `position:`, and optional `yaw` / `pitch` / `world`:
 *
 * ```yaml
 * spawn_points:
 *   - faction: attacker
 *     position: { x: -45.0, y: 64.0, z: 0.0 }
 *     yaw:   90.0     # optional, defaults to 0.0
 *     pitch:  0.0     # optional, defaults to 0.0
 *     world: arena_01 # optional, defaults to the stage's default world
 *   - faction: defender
 *     position: { x:  45.0, y: 64.0, z: 0.0 }
 *     yaw: 270.0
 * ```
 *
 * The flat shape lets the spawn selector strategies treat a spawn list as a
 * flat collection filtered by `faction`, without a `when (variant)` ladder.
 * Adding a new faction is a YAML edit + an enum variant; no DTO surgery.
 *
 * Per the pre-release posture (CLAUDE.md §Pre-release posture) this is a hard
 * rename — no dual-decoder for the old `type:` shape. Author YAMLs migrate
 * together with the code in Wave 5o.
 */
@Serializable
public data class SpawnPointYamlConfig(
    public val faction: String,
    public val position: Vec3YamlConfig,
    public val yaw: Float = 0.0f,
    public val pitch: Float = 0.0f,
    public val world: String? = null,
) {
    /**
     * Convert into a domain [SpawnPoint] using [defaultWorldId] when the YAML
     * omits `world:`. Faction is parsed case-insensitively so `attacker`,
     * `Attacker`, and `ATTACKER` are all accepted; unknown faction strings
     * throw `IllegalArgumentException`, which `ContentLoader` logs and counts
     * as a failed entry (the batch keeps going).
     */
    public fun toDomain(defaultWorldId: WorldId = DEFAULT_WORLD_ID): SpawnPoint {
        val resolvedWorld = world?.takeIf { it.isNotBlank() }?.let { WorldId(it) } ?: defaultWorldId
        return SpawnPoint(
            world = resolvedWorld,
            position = position.toVec3(),
            yaw = yaw,
            pitch = pitch,
            faction = parseFaction(faction),
        )
    }

    public companion object {
        public val DEFAULT_WORLD_ID: WorldId = WorldId("world")

        private fun parseFaction(value: String): SpawnFaction {
            val normalized = value.trim().uppercase()
            return SpawnFaction.entries.firstOrNull { it.name == normalized }
                ?: throw IllegalArgumentException(
                    "Unknown spawn faction '$value'. Expected one of: " +
                        SpawnFaction.entries.joinToString(", ") { it.name.lowercase() },
                )
        }
    }
}
