package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * A tournament's final match was decided (Wave 7b, ADR-0037). Published by
 * Match when the bracket's last round completes.
 *
 * @property winnerPlayerId the champion, or null if the bracket concluded with
 *   no decidable winner (degenerate single-entry / all-bye bracket).
 */
public data class TournamentConcluded(
    val tournamentId: String,
    val winnerPlayerId: PlayerId?,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global
}
