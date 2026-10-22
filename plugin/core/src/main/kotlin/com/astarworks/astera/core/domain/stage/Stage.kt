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
    public companion object {
        /** The server's default level name. */
        public const val DEFAULT_WORLD: String = "world"
    }
}
