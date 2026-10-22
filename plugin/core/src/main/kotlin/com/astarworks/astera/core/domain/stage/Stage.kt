package com.astarworks.astera.core.domain.stage

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.world.WorldId

/**
 * A named stage (map) used by matches or persistent places.
 *
 * WP9: [world] is the Bukkit world the stage is played in (default: the
 * server's main world, `world`); [spawnPoints] are per-team spawns plus lobby /
 * spectator points, all in that world. Geometry (schematic) is not modelled:
 * stages are hand-built in the world, [regions] only name areas of it.
 */
public data class Stage(
    val id: StageId,
    val displayNameKey: MessageKey,
    val regions: List<Region>,
    val spawnPoints: List<SpawnPoint> = emptyList(),
    val world: WorldId = WorldId(DEFAULT_WORLD),
) {
    /** The region the stage YAML named [name], or `null`. */
    public fun region(name: String): Region? = regions.firstOrNull { it.name == name }

    /** Every named region whose name starts with [prefix] (e.g. `wool-`), in declaration order. */
    public fun regionsWithPrefix(prefix: String): List<Region> =
        regions.filter { it.name?.startsWith(prefix) == true }

    public companion object {
        /** The server's default level name. */
        public const val DEFAULT_WORLD: String = "world"
    }
}
