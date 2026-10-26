package com.astarworks.astera.core.application.service.music

import com.astarworks.astera.core.application.port.outbound.music.IMusicGateway
import com.astarworks.astera.core.application.port.outbound.music.IMusicTrackRegistry
import com.astarworks.astera.core.application.port.outbound.music.IPlayerBgm
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.music.MusicHandle
import com.astarworks.astera.core.domain.music.MusicMood
import com.astarworks.astera.core.domain.music.MusicTrackId
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [IPlayerBgm] (ADR-0267): mood -> first track of that mood (by id) -> [IMusicGateway].
 * [gateway] is resolved per call so the Paper overlay is used. [musicVolumePercent] is the
 * player's `/astera volume music` value; `0` plays nothing (the opt-out).
 */
public class PlayerBgmController(
    private val tracks: IMusicTrackRegistry,
    private val gateway: () -> IMusicGateway,
    private val musicVolumePercent: (PlayerId) -> Int,
    private val crossfadeTicks: Long = 40L,
) : IPlayerBgm {

    private data class Session(val mood: MusicMood, val track: MusicTrackId?, val handle: MusicHandle?)

    private val log = LoggerFactory.getLogger(javaClass)
    private val sessions: MutableMap<PlayerId, Session> = ConcurrentHashMap()

    @Synchronized
    override fun switchTo(playerId: PlayerId, mood: MusicMood) {
        val current = sessions[playerId]
        if (current?.mood == mood && (current.handle != null || musicVolumePercent(playerId) == 0)) return
        val percent = musicVolumePercent(playerId).coerceIn(0, 100)
        val track = tracks.byMood(mood).minByOrNull { it.id.value }
        val out = gateway()
        if (track == null || percent == 0) {
            current?.handle?.let { out.stop(it, 0L) }
            sessions[playerId] = Session(mood, null, null)
            return
        }
        val volume = percent / 100.0
        val result = if (current?.handle != null) {
            out.crossfade(current.handle, track.id, crossfadeTicks)
        } else {
            out.play(playerId, track.id, volume)
        }
        when (result) {
            is Result.Success -> sessions[playerId] = Session(mood, track.id, result.value)
            is Result.Failure -> {
                log.debug("BGM {} for {} failed: {}", track.id, playerId, result.error)
                current?.handle?.let { out.stop(it, 0L) }
                sessions[playerId] = Session(mood, null, null)
            }
        }
    }

    @Synchronized
    override fun replay(playerId: PlayerId) {
        val current = sessions.remove(playerId)
        current?.handle?.let { gateway().stop(it, 0L) }
        switchTo(playerId, current?.mood ?: MusicMood.LOBBY)
    }

    @Synchronized
    override fun stop(playerId: PlayerId) {
        val current = sessions.remove(playerId) ?: return
        current.handle?.let { gateway().stop(it, 0L) }
    }

    override fun moodOf(playerId: PlayerId): MusicMood? = sessions[playerId]?.mood

    override fun trackOf(playerId: PlayerId): MusicTrackId? = sessions[playerId]?.track
}
