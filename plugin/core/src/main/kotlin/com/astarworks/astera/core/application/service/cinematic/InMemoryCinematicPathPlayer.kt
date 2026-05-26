package com.astarworks.astera.core.application.service.cinematic

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.cinematic.CinematicPlaybackError
import com.astarworks.astera.core.application.port.outbound.cinematic.ICinematicPathPlayer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.cinematic.CinematicHandle
import com.astarworks.astera.core.domain.cinematic.CinematicPath
import com.astarworks.astera.core.domain.event.CinematicEnded
import com.astarworks.astera.core.domain.event.CinematicStarted
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Default in-memory [ICinematicPathPlayer] (Wave 5cinematic, ADR-0110).
 *
 * Records active playback handles in a [ConcurrentHashMap] and publishes
 * [CinematicStarted] on `play` + [CinematicEnded] on `cancel`. **Does NOT**
 * schedule waypoint advancement — the in-memory impl is intentionally
 * lifecycle-only so tests can drive the play/cancel sequence without a
 * scheduler. The Paper adapter (Wave 6) drives waypoint advancement on
 * the server tick.
 *
 * Wired by `AsteraCorePlugin` at priority 0; Wave 6 `PaperCinematicPathPlayer`
 * shadows at higher priority.
 */
public class InMemoryCinematicPathPlayer(
    private val eventBus: IDomainEventBus,
    private val tickClock: () -> Long = { 0L },
) : ICinematicPathPlayer {

    private val live: ConcurrentHashMap<CinematicHandle, PlayerId> = ConcurrentHashMap()

    override fun play(
        playerId: PlayerId,
        path: CinematicPath,
    ): Result<CinematicHandle, CinematicPlaybackError> {
        // `CinematicPath.init` already enforces non-empty; this is a defensive
        // check in case a future caller bypasses the constructor.
        if (path.waypoints.isEmpty()) {
            return Result.failure(
                CinematicPlaybackError.EmptyPath("CinematicPath.waypoints must not be empty"),
            )
        }
        val handle = CinematicHandle(UUID.randomUUID().toString())
        live[handle] = playerId
        eventBus.publish(
            CinematicStarted(
                playerId = playerId,
                handle = handle,
                totalDurationTicks = path.totalDurationTicks,
                atTick = tickClock(),
            ),
        )
        return Result.success(handle)
    }

    override fun cancel(handle: CinematicHandle): Result<Unit, CinematicPlaybackError> {
        val playerId = live.remove(handle) ?: return Result.success(Unit)
        eventBus.publish(
            CinematicEnded(
                playerId = playerId,
                handle = handle,
                reasonKind = "cancelled",
                atTick = tickClock(),
            ),
        )
        return Result.success(Unit)
    }

    override fun active(): Collection<CinematicHandle> = live.keys.toList()
}
