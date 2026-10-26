package com.astarworks.astera.core.domain.event

import java.time.Instant

/**
 * A single-elimination tournament bracket was seeded and opened (Wave 7b,
 * ADR-0037). Published by Match (`CreateTournamentUseCase`).
 *
 * @property tournamentId the tournament's string id (mirrors
 *   `match.domain.tournament.TournamentId.value`; kept as a String so Core
 *   does not depend on Match types).
 * @property participantCount number of seeded players.
 * @property roundCount number of bracket rounds (`ceil(log2(participantCount))`).
 */
public data class TournamentStarted(
    val tournamentId: String,
    val participantCount: Int,
    val roundCount: Int,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global
}
