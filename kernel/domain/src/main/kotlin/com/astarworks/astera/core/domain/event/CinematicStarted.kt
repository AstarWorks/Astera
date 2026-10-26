package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.cinematic.CinematicHandle
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Cross-plugin event (Wave 5cinematic, ADR-0110): a cinematic path playback
 * started for [playerId].
 *
 * Fired by `ICinematicPathPlayer.play` impls (default
 * `InMemoryCinematicPathPlayer`; Wave 6 `PaperCinematicPathPlayer`).
 *
 * # Subscribers
 *
 *  - Audit log: `cinematic.started`.
 *  - Match HUD: hide normal HUD overlay for the duration.
 *  - Adaptive BGM: switch to `MusicMood.CINEMATIC` track if no overlay
 *    music was requested.
 */
public data class CinematicStarted(
    public val playerId: PlayerId,
    public val handle: CinematicHandle,
    public val totalDurationTicks: Long,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)

    init {
        require(totalDurationTicks >= 0L) {
            "CinematicStarted.totalDurationTicks must be >= 0 (got $totalDurationTicks)"
        }
    }
}
