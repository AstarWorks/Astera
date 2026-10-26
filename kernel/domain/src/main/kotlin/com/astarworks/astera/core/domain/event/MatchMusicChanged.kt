package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.game.InstanceId

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.music.MusicTrackId
import java.time.Instant

/**
 * Cross-plugin event (Wave 5cinematic, ADR-0110): the per-match BGM track
 * changed.
 *
 * Fired by
 * [com.astarworks.astera.core.application.port.outbound.music.IPerMatchBgmService]
 * impls on every observed track change for a given match. The first
 * track-set at match start has `previousTrack = null`.
 *
 * # When fired
 *
 *  - Match start (Wave 5gamemode IGameMode lifecycle hook).
 *  - Match phase transition (annihilation: build→engage→tier3→sudden death).
 *  - Adaptive BGM trigger fires (HP threshold crossed, mood change).
 *
 * # Subscribers
 *
 *  - HUD: optional "♪ Now Playing" toast.
 *  - Audit log: `match.music.changed`.
 *  - Per-viewer overrides (Wave 5y-ext): re-resolve per-player BGM.
 */
public data class MatchMusicChanged(
    public val matchId: MatchId,
    public val previousTrack: MusicTrackId?,
    public val newTrack: MusicTrackId,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Instance(InstanceId(matchId.value))

    init {
        require(previousTrack != newTrack) {
            "MatchMusicChanged requires distinct tracks (got both=$newTrack)"
        }
    }
}
