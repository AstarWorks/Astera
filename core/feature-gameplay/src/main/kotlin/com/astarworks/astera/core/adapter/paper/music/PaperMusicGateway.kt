package com.astarworks.astera.core.adapter.paper.music

import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.music.IMusicGateway
import com.astarworks.astera.core.application.port.outbound.music.IMusicTrackRegistry
import com.astarworks.astera.core.application.port.outbound.music.MusicError
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.music.MusicHandle
import com.astarworks.astera.core.domain.music.MusicSource
import com.astarworks.astera.core.domain.music.MusicTrack
import com.astarworks.astera.core.domain.music.MusicTrackId
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Paper [IMusicGateway] (ADR-0267). Plays a track's [MusicSource.Vanilla] sound event (a vanilla
 * music disc, no resource pack needed) on the client's MUSIC bus, so the client's own music slider
 * also applies. The sound follows the player (emitter overload), so walking away never attenuates it.
 *
 * Limits of the client protocol, stated rather than faked:
 *  - a playing sound cannot be faded: [crossfade] and [stop] cut the old track immediately and
 *    [fadeOutTicks] / [durationTicks] are accepted but not rendered;
 *  - [duck] / [unduck] are recorded and take effect when a looping track restarts.
 * Looping tracks restart every `durationTicks` (the length of the disc).
 */
public class PaperMusicGateway(
    private val server: Server,
    private val tracks: IMusicTrackRegistry,
    private val scheduler: IScheduler,
) : IMusicGateway {

    private class Session(
        val handle: MusicHandle,
        val playerId: PlayerId,
        val track: MusicTrack,
        val key: String,
        val volume: Double,
        var ducked: Double = 1.0,
        var loop: IScheduler.TaskHandle? = null,
    )

    private val live: MutableMap<MusicHandle, Session> = ConcurrentHashMap()

    override fun play(playerId: PlayerId, track: MusicTrackId, volume: Double): Result<MusicHandle, MusicError> {
        if (volume !in 0.0..1.0) {
            return Result.failure(MusicError.InvalidVolume("play.volume must be in 0.0..1.0 (got $volume)"))
        }
        val player = server.getPlayer(playerId.uuid) ?: return Result.failure(MusicError.UnknownPlayer(playerId))
        val def = tracks.find(track) ?: return Result.failure(MusicError.UnknownTrack(track))
        val key = (def.source as? MusicSource.Vanilla)?.soundKey
            ?: return Result.failure(MusicError.Platform("track ${def.id.value} has no playable source on Paper (${def.source})"))
        val session = Session(MusicHandle(UUID.randomUUID().toString()), playerId, def, key, volume * def.defaultVolume)
        live[session.handle] = session
        emit(player, session)
        val period = def.durationTicks
        if (def.loop && period != null && period > 0L) {
            session.loop = scheduler.runRepeating(ticks(period), ticks(period)) { restart(session) }
        }
        return Result.success(session.handle)
    }

    override fun crossfade(fromHandle: MusicHandle, toTrack: MusicTrackId, durationTicks: Long): Result<MusicHandle, MusicError> {
        if (durationTicks < 0L) {
            return Result.failure(MusicError.InvalidDuration("crossfade.durationTicks must be >= 0 (got $durationTicks)"))
        }
        val from = live[fromHandle] ?: return Result.failure(MusicError.UnknownHandle(fromHandle))
        if (tracks.find(toTrack) == null) return Result.failure(MusicError.UnknownTrack(toTrack))
        val started = play(from.playerId, toTrack, from.volume.coerceIn(0.0, 1.0))
        if (started is Result.Success) halt(from)
        return started
    }

    override fun duck(handle: MusicHandle, factor: Double, durationTicks: Long): Result<Unit, MusicError> {
        if (factor !in 0.0..1.0) return Result.failure(MusicError.InvalidVolume("duck.factor must be in 0.0..1.0 (got $factor)"))
        if (durationTicks < 0L) return Result.failure(MusicError.InvalidDuration("duck.durationTicks must be >= 0 (got $durationTicks)"))
        val s = live[handle] ?: return Result.failure(MusicError.UnknownHandle(handle))
        s.ducked = factor
        return Result.success(Unit)
    }

    override fun unduck(handle: MusicHandle, durationTicks: Long): Result<Unit, MusicError> {
        if (durationTicks < 0L) return Result.failure(MusicError.InvalidDuration("unduck.durationTicks must be >= 0 (got $durationTicks)"))
        live[handle]?.ducked = 1.0
        return Result.success(Unit)
    }

    override fun stop(handle: MusicHandle, fadeOutTicks: Long): Result<Unit, MusicError> {
        if (fadeOutTicks < 0L) return Result.failure(MusicError.InvalidDuration("stop.fadeOutTicks must be >= 0 (got $fadeOutTicks)"))
        live[handle]?.let(::halt)
        return Result.success(Unit)
    }

    override fun active(playerId: PlayerId): Collection<MusicHandle> =
        live.values.filter { it.playerId == playerId }.map { it.handle }

    private fun halt(session: Session) {
        live.remove(session.handle)
        session.loop?.cancel()
        server.getPlayer(session.playerId.uuid)?.stopSound(session.key, SoundCategory.MUSIC)
    }

    private fun restart(session: Session) {
        val player = server.getPlayer(session.playerId.uuid)
        if (player == null || !live.containsKey(session.handle)) {
            halt(session)
            return
        }
        emit(player, session)
    }

    private fun emit(player: Player, session: Session) {
        val volume = (session.volume * session.ducked).toFloat().coerceIn(0f, 1f)
        player.playSound(player, session.key, SoundCategory.MUSIC, volume, 1.0f)
    }
}
