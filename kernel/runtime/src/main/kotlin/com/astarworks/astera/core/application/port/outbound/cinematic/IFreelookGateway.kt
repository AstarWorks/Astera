package com.astarworks.astera.core.application.port.outbound.cinematic

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.cinematic.FreelookSession
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: free-look mode for a player (Wave 5cinematic, ADR-0110).
 *
 * Free-look = movement is frozen (Wave 6 adapter: Paper movement listener
 * cancels any non-look input), but the player keeps free mouse / pitch /
 * yaw look. Used for tutorial side-scenes ("look around the lobby") and
 * pre-match staging.
 *
 * Distinct from [ICameraGateway.freeze] — that locks the camera entirely
 * (no look). Distinct from spectator mode — the player stays as their
 * normal gamemode + entity, just movement-frozen.
 */
public interface IFreelookGateway {

    /**
     * Enter free-look mode for [playerId] anchored at [anchorPosition].
     * Returns the session value. Replacing an existing session for the same
     * player implicitly disables the previous one (no double-disable event).
     */
    public fun enableFreelook(
        playerId: PlayerId,
        anchorPosition: Vec3,
    ): Result<FreelookSession, FreelookError>

    /**
     * Disable free-look mode for [playerId]. Idempotent — disabling a player
     * not currently in free-look returns Success.
     */
    public fun disableFreelook(playerId: PlayerId): Result<Unit, FreelookError>

    /** Current session for [playerId], or `null` if not in free-look. */
    public fun session(playerId: PlayerId): FreelookSession?

    /** Snapshot of all currently free-looking players. */
    public fun all(): Collection<FreelookSession>
}

public sealed class FreelookError {
    public data class UnknownPlayer(public val playerId: PlayerId) : FreelookError()
    public data class Platform(public val detail: String) : FreelookError()
}
