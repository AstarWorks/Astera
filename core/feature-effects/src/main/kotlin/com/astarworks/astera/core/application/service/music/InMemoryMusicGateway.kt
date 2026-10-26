package com.astarworks.astera.core.application.service.music

import com.astarworks.astera.core.application.port.outbound.music.IMusicGateway
import com.astarworks.astera.core.application.port.outbound.music.IMusicTrackRegistry
import com.astarworks.astera.core.application.port.outbound.music.MusicError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.music.MusicHandle
import com.astarworks.astera.core.domain.music.MusicTrackId
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Default in-memory [IMusicGateway] (Wave 5cinematic, ADR-0110).
 *
 * Validates inputs against [IMusicTrackRegistry] so an `UnknownTrack` failure
 * surfaces early. Keeps a per-handle record of `(playerId, trackId, ducked,
 * stopped)`. The in-memory impl does not actually emit sound — Wave 6
 * `PaperMusicGateway` calls `Player.playSound` from the resolved
 * `MusicSource.ResourcePack.path`.
 *
 * **Concurrency**: multiple handles per player are allowed (overlay sting
 * on top of looping BGM).
 */
public class InMemoryMusicGateway(
    private val tracks: IMusicTrackRegistry,
) : IMusicGateway {

    private data class Playing(
        val playerId: PlayerId,
        val trackId: MusicTrackId,
        var ducked: Boolean = false,
        var stopped: Boolean = false,
    )

    private val live: ConcurrentHashMap<MusicHandle, Playing> = ConcurrentHashMap()

    override fun play(
        playerId: PlayerId,
        track: MusicTrackId,
        volume: Double,
    ): Result<MusicHandle, MusicError> {
        if (volume !in 0.0..1.0) {
            return Result.failure(
                MusicError.InvalidVolume("play.volume must be in 0.0..1.0 (got $volume)"),
            )
        }
        tracks.find(track)
            ?: return Result.failure(MusicError.UnknownTrack(track))
        val handle = mintHandle()
        live[handle] = Playing(playerId, track)
        return Result.success(handle)
    }

    override fun crossfade(
        fromHandle: MusicHandle,
        toTrack: MusicTrackId,
        durationTicks: Long,
    ): Result<MusicHandle, MusicError> {
        if (durationTicks < 0L) {
            return Result.failure(
                MusicError.InvalidDuration("crossfade.durationTicks must be >= 0 (got $durationTicks)"),
            )
        }
        val fromState = live[fromHandle]
            ?: return Result.failure(MusicError.UnknownHandle(fromHandle))
        tracks.find(toTrack)
            ?: return Result.failure(MusicError.UnknownTrack(toTrack))
        val newHandle = mintHandle()
        live[newHandle] = Playing(fromState.playerId, toTrack)
        // Mark old as stopped; remove from live so `active()` no longer reports it.
        live.remove(fromHandle)
        return Result.success(newHandle)
    }

    override fun duck(
        handle: MusicHandle,
        factor: Double,
        durationTicks: Long,
    ): Result<Unit, MusicError> {
        if (factor !in 0.0..1.0) {
            return Result.failure(
                MusicError.InvalidVolume("duck.factor must be in 0.0..1.0 (got $factor)"),
            )
        }
        if (durationTicks < 0L) {
            return Result.failure(
                MusicError.InvalidDuration("duck.durationTicks must be >= 0 (got $durationTicks)"),
            )
        }
        val state = live[handle] ?: return Result.failure(MusicError.UnknownHandle(handle))
        state.ducked = true
        return Result.success(Unit)
    }

    override fun unduck(
        handle: MusicHandle,
        durationTicks: Long,
    ): Result<Unit, MusicError> {
        if (durationTicks < 0L) {
            return Result.failure(
                MusicError.InvalidDuration("unduck.durationTicks must be >= 0 (got $durationTicks)"),
            )
        }
        val state = live[handle] ?: return Result.success(Unit)
        state.ducked = false
        return Result.success(Unit)
    }

    override fun stop(handle: MusicHandle, fadeOutTicks: Long): Result<Unit, MusicError> {
        if (fadeOutTicks < 0L) {
            return Result.failure(
                MusicError.InvalidDuration("stop.fadeOutTicks must be >= 0 (got $fadeOutTicks)"),
            )
        }
        live.remove(handle)
        return Result.success(Unit)
    }

    override fun active(playerId: PlayerId): Collection<MusicHandle> =
        live.entries.filter { it.value.playerId == playerId && !it.value.stopped }
            .map { it.key }

    private fun mintHandle(): MusicHandle = MusicHandle(UUID.randomUUID().toString())
}
