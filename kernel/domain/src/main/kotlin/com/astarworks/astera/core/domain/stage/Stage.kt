package com.astarworks.astera.core.domain.stage

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.world.WorldId

/**
 * A named stage (map) used by matches or persistent places.
 *
 * WP9: [world] is the Bukkit world the stage is played in (default: the
 * server's main world, `world`); [spawnPoints] are per-team spawns plus lobby /
 * spectator points, all in that world. [regions] name areas of it. The geometry comes from
 * [layout] (sky islands the server builds, ADR-0246); a stage without a layout is hand-built.
 */
public data class Stage(
    val id: StageId,
    val displayNameKey: MessageKey,
    val regions: List<Region>,
    val spawnPoints: List<SpawnPoint> = emptyList(),
    val world: WorldId = WorldId(DEFAULT_WORLD),
    val pillars: List<StagePillar> = emptyList(),
    /** Sky-island block layout a Paper-side builder places into [world] (ADR-0246); `null` = hand-built stage. */
    val layout: ArenaLayout? = null,
    /** Content identity stays stable while [id] identifies one placed instance. */
    val templateId: StageId = id,
    val origin: com.astarworks.astera.core.domain.geometry.Vec3 = com.astarworks.astera.core.domain.geometry.Vec3(0.0, 0.0, 0.0),
    val warmSlots: Int? = null,
) {
    /** The region the stage YAML named [name], or `null`. */
    public fun region(name: String): Region? = regions.firstOrNull { it.name == name }

    /** Every named region whose name starts with [prefix] (e.g. `wool-`), in declaration order. */
    public fun regionsWithPrefix(prefix: String): List<Region> =
        regions.filter { it.name?.startsWith(prefix) == true }
    init {
        val keys = pillars.map { it.team to it.tier }
        require(keys.size == keys.toSet().size) { "Stage '${id.value}' declares a pillar (team, tier) twice" }
    }

    public companion object {
        /** The server's default level name. */
        public const val DEFAULT_WORLD: String = "world"
    }
}
