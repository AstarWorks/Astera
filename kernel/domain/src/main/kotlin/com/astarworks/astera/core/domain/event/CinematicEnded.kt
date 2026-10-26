package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.cinematic.CinematicHandle
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Cross-plugin event (Wave 5cinematic, ADR-0110): a cinematic path playback
 * finished or was cancelled for [playerId].
 *
 * [reasonKind] is a snake_case discriminator with allowed values:
 *  - `"completed"` — natural end (all waypoints traversed).
 *  - `"cancelled"` — caller invoked `ICinematicPathPlayer.cancel(handle)`
 *    (skip-cutscene button, player disconnect mid-flight).
 *  - `"failed"` — backend failure (entity target despawned, world unloaded).
 *
 * String-typed reason intentionally mirrors `IslandUnloaded.reasonKind`
 * (ADR-0107) so subscribers can route via a single `when` block.
 */
public data class CinematicEnded(
    public val playerId: PlayerId,
    public val handle: CinematicHandle,
    public val reasonKind: String,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)

    init {
        require(reasonKind.isNotBlank()) {
            "CinematicEnded.reasonKind must not be blank"
        }
        require(reasonKind in ALLOWED_REASONS) {
            "CinematicEnded.reasonKind must be one of $ALLOWED_REASONS (got '$reasonKind')"
        }
    }

    public companion object {
        public val ALLOWED_REASONS: Set<String> = setOf("completed", "cancelled", "failed")
    }
}
