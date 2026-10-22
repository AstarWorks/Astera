package com.astarworks.astera.match.application.config

import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.SpawnPoint
import com.astarworks.astera.core.domain.stage.SpawnRole
import com.astarworks.astera.core.domain.world.WorldId
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for one `spawn_points:` entry of a stage (WP9).
 *
 * ```yaml
 * spawn_points:
 *   - team: red                 # per-team spawn (any team id the match uses)
 *     position: { x: -45.0, y: -60.0, z: 0.0 }
 *     yaw: 90.0                 # optional
 *   - role: lobby               # team-less: lobby | spectator
 *     position: { x: 0.0, y: -60.0, z: 0.0 }
 * ```
 *
 * Exactly one of `team` / `role` is given. All spawns are placed in the
 * stage's `world` (see [StageYamlConfig]).
 */
@Serializable
public data class SpawnPointYamlConfig(
    public val team: String? = null,
    public val role: String? = null,
    public val position: Vec3YamlConfig,
    public val yaw: Float = 0.0f,
    public val pitch: Float = 0.0f,
) {
    /** Invalid combinations throw `IllegalArgumentException` (logged by `ContentLoader`). */
    public fun toDomain(world: WorldId): SpawnPoint {
        require((team == null) != (role == null)) { "spawn point needs exactly one of 'team' or 'role'" }
        if (team != null) {
            return SpawnPoint(world, position.toVec3(), yaw, pitch, SpawnRole.TEAM, TeamId(team))
        }
        val parsed = when (role!!.trim().lowercase()) {
            "lobby" -> SpawnRole.LOBBY
            "spectator" -> SpawnRole.SPECTATOR
            else -> throw IllegalArgumentException("Unknown spawn role '$role'. Expected: lobby, spectator")
        }
        return SpawnPoint(world, position.toVec3(), yaw, pitch, parsed)
    }
}
