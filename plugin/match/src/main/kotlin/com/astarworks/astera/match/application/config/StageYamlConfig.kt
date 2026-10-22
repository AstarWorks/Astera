package com.astarworks.astera.match.application.config

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.WorldId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for `content/stages/` definition files (ADR-0003 / ADR-0016).
 *
 * ```yaml
 * id: example-arena
 * display_name_key: astera.stage.example-arena.name
 * world: world            # optional; Bukkit world name, default = the main world "world"
 * regions:
 *   - name: full-arena
 *     min: { x: -50.0, y: -60.0, z: -50.0 }
 *     max: { x:  50.0, y: -30.0, z:  50.0 }
 * spawn_points: [...]     # see [SpawnPointYamlConfig]
 * ```
 *
 * `name` on a [RegionYamlConfig] is informational: other YAMLs reference areas
 * of a stage by it. WP9 decision: stages are hand-built in their world, so
 * `schematic` / `core` are not part of the schema (a strict decode rejects
 * them); they return only together with a consumer.
 */
@Serializable
public data class StageYamlConfig(
    public val id: String,
    @SerialName("display_name_key") public val displayNameKey: String,
    public val world: String = Stage.DEFAULT_WORLD,
    public val regions: List<RegionYamlConfig> = emptyList(),
    @SerialName("spawn_points") public val spawnPoints: List<SpawnPointYamlConfig> = emptyList(),
) {
    /**
     * Convert the parsed DTO into a domain [Stage]. Any kebab-case / i18n key
     * violation is surfaced as `IllegalArgumentException`, which
     * [com.astarworks.astera.core.application.service.ContentLoader] logs and
     * counts as a failure (the batch keeps going).
     */
    public fun toSpec(): Stage {
        val worldId = WorldId(world)
        return Stage(
            id = StageId(id),
            displayNameKey = MessageKey(displayNameKey),
            regions = regions.map { it.toRegion() },
            spawnPoints = spawnPoints.map { it.toDomain(worldId) },
            world = worldId,
        )
    }
}

/**
 * YAML-shaped DTO for a single [Region] inside a stage.
 *
 * `name` is informational. The AABB invariant (`min <= max` on every axis) is
 * enforced by [Region]'s `init` block, which throws if violated.
 */
@Serializable
public data class RegionYamlConfig(
    public val name: String? = null,
    public val min: Vec3YamlConfig,
    public val max: Vec3YamlConfig,
) {
    public fun toRegion(): Region = Region(min = min.toVec3(), max = max.toVec3(), name = name)
}

/**
 * YAML-shaped DTO for a [Vec3]. Defined here (rather than annotating the
 * domain [Vec3] with `@Serializable`) so the domain stays free of
 * serialization annotations per ADR-0003.
 */
@Serializable
public data class Vec3YamlConfig(
    public val x: Double,
    public val y: Double,
    public val z: Double,
) {
    public fun toVec3(): Vec3 = Vec3(x = x, y = y, z = z)
}
