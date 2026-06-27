package com.astarworks.astera.match.application.eventbus.listeners

import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.match.application.usecase.rating.UpdateRatingOnMatchEndedUseCase
import org.slf4j.LoggerFactory

/**
 * On [MatchEnded] applies Elo updates to every participant via
 * [UpdateRatingOnMatchEndedUseCase] (Wave 7b, ADR-0037).
 *
 * Mirrors `stats`'s `RecordMatchResultOnMatchEndedListener`: a plain class with
 * a `handle(event)` entry point, registered against the bus in Phase C wiring.
 * The use-case call is wrapped in `runCatching` so a failure to rate one match
 * never propagates back into the publisher / breaks sibling listeners.
 *
 * silly-knuth §5: in-process only, no broadcaster.
 */
public class UpdateRatingOnMatchEndedListener(
    private val updateRating: UpdateRatingOnMatchEndedUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: MatchEnded) {
        runCatching { updateRating.onMatchEnded(event) }
            .onFailure { err ->
                log.debug(
                    "UpdateRatingOnMatchEndedListener: failed for match={} err={}",
                    event.matchId, err.message,
                )
            }
    }
}
