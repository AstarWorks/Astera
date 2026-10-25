package com.astarworks.astera.core.application.service.music

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.music.IMusicTrackRegistry
import com.astarworks.astera.core.application.port.outbound.music.IPerMatchBgmService
import com.astarworks.astera.core.application.port.outbound.music.PerMatchBgmError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchMusicChanged
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.music.MusicTrackId
import java.util.concurrent.ConcurrentHashMap

/**
 * Default in-memory [IPerMatchBgmService] (Wave 5cinematic, ADR-0110).
 *
 * Records the current track per match and publishes [MatchMusicChanged] on
 * every transition. Does not drive [com.astarworks.astera.core.application.port.outbound.music.IMusicGateway]
 * itself — Wave 6 (or a subscriber in Wave 5gamemode) bridges the event to
 * per-participant playback.
 *
 * # Why a separate service from the gateway
 *
 * The gateway operates per-player. The per-match state machine operates
 * per-match. Separating them lets the test suite drive match-level
 * transitions without provisioning a per-player playback fake.
 */
public class InMemoryPerMatchBgmService(
    private val tracks: IMusicTrackRegistry,
    private val eventBus: IDomainEventBus,
    private val tickClock: () -> Long = { 0L },
) : IPerMatchBgmService {

    private val current: ConcurrentHashMap<MatchId, MusicTrackId> = ConcurrentHashMap()

    override fun setTrack(matchId: MatchId, newTrack: MusicTrackId): Result<Unit, PerMatchBgmError> {
        tracks.find(newTrack)
            ?: return Result.failure(PerMatchBgmError.UnknownTrack(newTrack))
        val previous = current[matchId]
        if (previous == newTrack) {
            return Result.success(Unit)
        }
        current[matchId] = newTrack
        eventBus.publish(
            MatchMusicChanged(
                matchId = matchId,
                previousTrack = previous,
                newTrack = newTrack,
                atTick = tickClock(),
            ),
        )
        return Result.success(Unit)
    }

    override fun stop(matchId: MatchId): Result<Unit, PerMatchBgmError> {
        // Removing the entry yields a no-op for already-stopped matches.
        // We do NOT publish a synthetic "stopped" MatchMusicChanged here —
        // the event requires distinct old/new tracks and a sentinel "no track"
        // value would pollute the type. Subscribers learn about stops via the
        // match-end domain event from `plugin:match`.
        current.remove(matchId)
        return Result.success(Unit)
    }

    override fun current(matchId: MatchId): MusicTrackId? = current[matchId]
}
