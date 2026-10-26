package com.astarworks.astera.core.domain.event

import java.time.Instant

/**
 * A tournament round completed and the next round was populated with winners
 * (Wave 7b, ADR-0037). Published by Match when the last match of a round is
 * reported.
 *
 * @property roundIndex zero-based index of the round that just completed.
 */
public data class TournamentRoundAdvanced(
    val tournamentId: String,
    val roundIndex: Int,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global
}
