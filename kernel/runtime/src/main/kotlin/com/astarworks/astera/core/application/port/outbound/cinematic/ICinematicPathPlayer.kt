package com.astarworks.astera.core.application.port.outbound.cinematic

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.cinematic.CinematicHandle
import com.astarworks.astera.core.domain.cinematic.CinematicPath
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: schedule a path-based camera move for a player
 * (Wave 5cinematic, ADR-0110).
 *
 * Higher-level orchestration on top of [ICameraGateway] — composes a
 * sequence of `lockCamera` / `panCamera` calls per [CinematicPath]
 * waypoints, advancing on the configured tick schedule.
 *
 * Wave 5cinematic ships `InMemoryCinematicPathPlayer`. Wave 6 wires this
 * to the scheduler and Paper packet flow.
 */
public interface ICinematicPathPlayer {

    /**
     * Begin playing [path] for [playerId]. Returns a handle the caller
     * stores to cancel mid-flight. Publishes
     * [com.astarworks.astera.core.domain.event.CinematicStarted] synchronously
     * and [com.astarworks.astera.core.domain.event.CinematicEnded] when the
     * path completes or is cancelled.
     */
    public fun play(
        playerId: PlayerId,
        path: CinematicPath,
    ): Result<CinematicHandle, CinematicPlaybackError>

    /**
     * Cancel an in-flight cinematic. Idempotent: cancelling an already-finished
     * handle returns Success without firing a second `CinematicEnded`.
     */
    public fun cancel(handle: CinematicHandle): Result<Unit, CinematicPlaybackError>

    /**
     * Snapshot of currently-playing cinematic handles (admin / debug surface).
     */
    public fun active(): Collection<CinematicHandle>
}

public sealed class CinematicPlaybackError {
    public data class UnknownPlayer(public val playerId: PlayerId) : CinematicPlaybackError()
    public data class UnknownHandle(public val handle: CinematicHandle) : CinematicPlaybackError()
    public data class EmptyPath(public val detail: String) : CinematicPlaybackError()
    public data class Platform(public val detail: String) : CinematicPlaybackError()
}
