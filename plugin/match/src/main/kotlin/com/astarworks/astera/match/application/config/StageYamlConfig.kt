package com.astarworks.astera.match.application.config

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.nexus.NexusPillarTier
import com.astarworks.astera.core.domain.stage.ArenaBridge
import com.astarworks.astera.core.domain.stage.ArenaIsland
import com.astarworks.astera.core.domain.stage.ArenaIslandKind
import com.astarworks.astera.core.domain.stage.ArenaLandmark
import com.astarworks.astera.core.domain.stage.ArenaLandmarkKind
import com.astarworks.astera.core.domain.stage.ArenaTheme
import com.astarworks.astera.core.domain.stage.ArenaStructurePlacement
import com.astarworks.astera.core.domain.stage.ArenaLayout
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.stage.StagePillar
import com.astarworks.astera.core.domain.world.BlockType
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
 * pillars: [...]          # Annihilation pillar sites, see [PillarYamlConfig]
 * layout: {...}           # sky-island block layout the server builds, see [ArenaLayoutYamlConfig] (ADR-0246)
 * ```
 *
 * `name` on a [RegionYamlConfig] is informational: other YAMLs reference areas
 * of a stage by it. There is no schematic / `core` field: the geometry is the
 * `layout`, built by the server (ADR-0246); a stage without one is hand-built.
 */
@Serializable
public data class StageYamlConfig(
    public val id: String,
    @SerialName("display_name_key") public val displayNameKey: String,
    public val world: String = Stage.DEFAULT_WORLD,
    public val regions: List<RegionYamlConfig> = emptyList(),
    @SerialName("spawn_points") public val spawnPoints: List<SpawnPointYamlConfig> = emptyList(),
    public val pillars: List<PillarYamlConfig> = emptyList(),
    public val layout: ArenaLayoutYamlConfig? = null,
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
            pillars = pillars.map { it.toDomain() },
            layout = layout?.toDomain(),
        )
    }
}

/**
 * YAML-shaped DTO for a stage's `layout:` (ADR-0246): floating islands, bridges and decorated landmarks a
 * Paper-side builder turns into blocks. Spawns, `core-*` / `wool-*` / ... regions and pillars stay on the stage
 * and are decorated by the builder, so the anchors sit at exactly the built positions.
 *
 * ```yaml
 * layout:
 *   marker_version: 1        # bump to force a rebuild when the builder code changed the look
 *   seed: 20261023
 *   surface_y: 64            # top layer of every island; players stand at 65
 *   kill_y: 30               # void-fall kill plane (default surface_y - 34)
 *   islands:
 *     - { id: red, kind: base, team: red, center: { x: -30, z: 0 }, radius: 12 }
 *     - { id: center, kind: center, center: { x: 0, z: 0 }, radius: 10 }
 *   bridges:
 *     - { from: red, to: center, width: 3 }
 *   landmarks:
 *     - { kind: generator, x: 0, z: -26 }
 * ```
 */
@Serializable
public data class ArenaLayoutYamlConfig(
    @SerialName("marker_version") public val markerVersion: Int = 1,
    public val theme: String = "temperate",
    public val structures: List<ArenaStructureYamlConfig> = emptyList(),
    public val seed: Long,
    @SerialName("surface_y") public val surfaceY: Int = 64,
    @SerialName("kill_y") public val killY: Int? = null,
    public val islands: List<ArenaIslandYamlConfig>,
    public val bridges: List<ArenaBridgeYamlConfig> = emptyList(),
    public val landmarks: List<ArenaLandmarkYamlConfig> = emptyList(),
) {
    public fun toDomain(): ArenaLayout = ArenaLayout(
        markerVersion = markerVersion,
        theme = ArenaTheme.valueOf(theme.uppercase()),
        structures = structures.map { ArenaStructurePlacement(it.part, it.x, it.y, it.z, it.rotation, it.palette) },
        seed = seed,
        surfaceY = surfaceY,
        killY = killY ?: (surfaceY - ArenaLayout.DEFAULT_KILL_DEPTH),
        islands = islands.map { it.toDomain() },
        bridges = bridges.map { ArenaBridge(it.from, it.to, it.width) },
        landmarks = landmarks.map { it.toDomain() },
    )
}

@Serializable
public data class ArenaIslandYamlConfig(
    public val id: String,
    public val kind: String,
    public val center: ArenaXzYamlConfig,
    public val radius: Double,
    public val team: String? = null,
    public val accent: String? = null,
) {
    public fun toDomain(): ArenaIsland = ArenaIsland(
        id = id,
        kind = parseKind(kind),
        x = center.x,
        z = center.z,
        radius = radius,
        team = team?.let { TeamId(it) },
        accent = accent,
    )

    private fun parseKind(raw: String): ArenaIslandKind =
        ArenaIslandKind.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown island kind '$raw'. Expected: ${ArenaIslandKind.entries.joinToString { it.name.lowercase() }}")
}

@Serializable
public data class ArenaXzYamlConfig(public val x: Int, public val z: Int)

@Serializable
public data class ArenaBridgeYamlConfig(
    public val from: String,
    public val to: String,
    public val width: Int = 3,
)

@Serializable
public data class ArenaLandmarkYamlConfig(
    public val kind: String,
    public val x: Int,
    public val z: Int,
) {
    public fun toDomain(): ArenaLandmark {
        val parsed = ArenaLandmarkKind.entries.firstOrNull { it.name.equals(kind.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown landmark kind '$kind'. Expected: ${ArenaLandmarkKind.entries.joinToString { it.name.lowercase() }}")
        return ArenaLandmark(parsed, x, z)
    }
}

/**
 * YAML-shaped DTO for one `pillars:` entry of a stage (WP10): the block of one
 * team's Annihilation pillar.
 *
 * ```yaml
 * pillars:
 *   - team: red
 *     tier: 1                              # 1 (outer) .. 3 (the Nexus proper)
 *     position: { x: 0, y: -59, z: -41 }   # block coordinate in the stage's world
 *     block: minecraft:obsidian
 *     hp: 800                              # optional; default = the mode's pillar_hp
 * ```
 */
@Serializable
public data class PillarYamlConfig(
    public val team: String,
    public val tier: Int,
    public val position: Vec3YamlConfig,
    public val block: String,
    public val hp: Int? = null,
) {
    public fun toDomain(): StagePillar = StagePillar(
        team = TeamId(team),
        tier = requireNotNull(NexusPillarTier.fromOrdinal(tier)) { "pillar tier must be 1..3 (got $tier)" },
        position = position.toVec3(),
        block = BlockType(block),
        hp = hp,
    )
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

/** Coordinates relative to surface_y; palette entries override the stage theme for this placement. */
@Serializable
public data class ArenaStructureYamlConfig(
    public val part: String,
    public val x: Int,
    public val y: Int = 0,
    public val z: Int,
    public val rotation: Int = 0,
    public val palette: Map<String, String> = emptyMap(),
)
