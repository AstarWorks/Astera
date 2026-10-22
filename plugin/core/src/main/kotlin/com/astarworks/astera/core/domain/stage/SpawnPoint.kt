package com.astarworks.astera.core.domain.stage

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * A position in a [Stage] where a player can be teleported during a match
 * lifecycle event (entry, respawn, post-match return to lobby).
 *
 * WP9: spawns are per team. A [SpawnRole.TEAM] spawn belongs to exactly one
 * [team] (any number of teams: 2-team and 3-team modes share the model); the
 * [SpawnRole.LOBBY] and [SpawnRole.SPECTATOR] spawns are team-less.
 *
 * @property world The Bukkit world the spawn lives in (the stage's world).
 * @property position Spawn coordinates in [world].
 * @property yaw Facing yaw in degrees (Bukkit convention).
 * @property pitch Facing pitch in degrees.
 * @property role Which kind of spawn this is.
 * @property team Owning team for [SpawnRole.TEAM]; `null` otherwise.
 */
public data class SpawnPoint(
    public val world: WorldId,
    public val position: Vec3,
    public val yaw: Float = 0.0f,
    public val pitch: Float = 0.0f,
    public val role: SpawnRole = SpawnRole.LOBBY,
    public val team: TeamId? = null,
) {
    init {
        require((role == SpawnRole.TEAM) == (team != null)) {
            "team must be set exactly for TEAM spawns (role=$role, team=$team)"
        }
    }

    /** Position-only accessor for call sites that only care about coordinates. */
    public val pos: Vec3 get() = position
}

/** What a [SpawnPoint] is for. */
public enum class SpawnRole {
    /** A spawn owned by one team (see [SpawnPoint.team]). */
    TEAM,

    /** Pre-match lobby / staging area. */
    LOBBY,

    /** Spectator camera / dead-player view. */
    SPECTATOR,
}
