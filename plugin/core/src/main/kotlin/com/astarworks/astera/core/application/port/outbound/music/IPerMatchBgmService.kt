package com.astarworks.astera.core.application.port.outbound.music

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.music.MusicTrackId

/**
 * Outbound port: per-match BGM lifecycle (Wave 5cinematic, ADR-0110).
 *
 * Subscribed to by Wave 5gamemode `IGameMode` lifecycle hooks (match start,
 * phase transition, match end). On every observed change, fires
 * [com.astarworks.astera.core.domain.event.MatchMusicChanged] so HUD +
 * audit subscribers can react.
 *
 * Wave 5cinematic ships `InMemoryPerMatchBgmService`. The runtime currently
 * stops the previous track + plays the new one for every participant via
 * [IMusicGateway]; Wave 6 may wrap this with a cross-fade.
 */
public interface IPerMatchBgmService {

    /**
     * Set the active BGM track for [matchId]. Publishes [com.astarworks.astera.core.domain.event.MatchMusicChanged]
     * if the new track differs from the current one (no-op + Success if
     * setting the same track twice).
     *
     * Per-participant playback / cross-fade is the [IMusicGateway]'s job —
     * this service is the per-match state machine layer.
     */
    public fun setTrack(
        matchId: MatchId,
        newTrack: MusicTrackId,
    ): Result<Unit, PerMatchBgmError>

    /**
     * Stop the BGM for [matchId] (match end / cancellation). Publishes a
     * final [com.astarworks.astera.core.domain.event.MatchMusicChanged] if
     * there was an active track. Idempotent.
     */
    public fun stop(matchId: MatchId): Result<Unit, PerMatchBgmError>

    /** Currently-playing track for [matchId], or null if none. */
    public fun current(matchId: MatchId): MusicTrackId?
}

public sealed class PerMatchBgmError {
    public data class UnknownTrack(public val trackId: MusicTrackId) : PerMatchBgmError()
    public data class Platform(public val detail: String) : PerMatchBgmError()
}
