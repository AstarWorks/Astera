package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.match.domain.event.PlayerForfeited
import com.astarworks.astera.stats.application.usecase.stats.RecordMatchPlayedUseCase
import com.astarworks.astera.stats.application.usecase.stats.RecordMatchResultUseCase

/**
 * A participant who forfeited a running match (left and did not come back within the reconnect window,
 * MODE-14) is out of the roster, so the match-end listeners never see them: record the match as played
 * and as a loss here. No rewards: those are paid from the match summary, which lists only the roster.
 */
public class RecordForfeitLossListener(
    private val recordMatchPlayed: RecordMatchPlayedUseCase,
    private val recordMatchResult: RecordMatchResultUseCase,
) {
    public fun handle(event: PlayerForfeited) {
        recordMatchPlayed.execute(event.playerId)
        recordMatchResult.execute(event.playerId, RecordMatchResultUseCase.Outcome.LOSS)
    }
}
