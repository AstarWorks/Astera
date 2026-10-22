package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.match.result.MatchSummary
import java.time.Instant

/**
 * The finished match's tallies, team scores and MVP, published once per match
 * by the match plugin right after [MatchEnded] (the result screen is shown
 * first, then this event lets the economy pay out and the "last match"
 * record be written).
 */
public data class MatchSummaryReady(
    val summary: MatchSummary,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
