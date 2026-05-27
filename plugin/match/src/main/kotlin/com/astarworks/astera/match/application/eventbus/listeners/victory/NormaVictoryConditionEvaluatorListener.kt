package com.astarworks.astera.match.application.eventbus.listeners.victory

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.match.application.usecase.match.SwitchPhaseUseCase
import com.astarworks.astera.match.application.usecase.match.VictoryConditionEvaluator
import com.astarworks.astera.match.domain.event.EntityPickedUpItem
import com.astarworks.astera.match.domain.match.MatchStateMachine
import org.slf4j.LoggerFactory

/**
 * Auto-fire [MatchStateMachine.TransitionTrigger.ScoreThresholdReached]
 * when a Norma score crosses the win threshold (Wave 5complete,
 * ADR-0117 §Axis 5).
 *
 * # Why this matters
 *
 * Pre-Wave-5complete, Norma matches relied on an admin running
 * `/astera-match phase` after watching the score climb — there was no
 * subscriber re-evaluating [VictoryConditionEvaluator] on score change.
 * This listener closes that gap by re-evaluating after every
 * [EntityPickedUpItem] (the Wave 5c star/deposit pipeline path) and
 * triggering the phase transition when a unique winner emerges.
 *
 * # No-op fast path
 *
 *  - Match unknown → drop.
 *  - Match already past Active phase → drop (transition is one-way).
 *  - No unique winner yet (no team at threshold, or tied) → drop.
 *
 * The evaluator is pure (no I/O), so re-running on every pickup is
 * cheap — Phase 1 PoC star pickups fire at ~1/s across all teams.
 */
public class NormaVictoryConditionEvaluatorListener(
    private val matches: IMatchRepository,
    private val switchPhase: SwitchPhaseUseCase,
    private val evaluator: VictoryConditionEvaluator = VictoryConditionEvaluator(),
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: EntityPickedUpItem) {
        val match = matches.findById(event.matchId) ?: return

        val winner = evaluator.evaluate(match) ?: return

        log.info(
            "Norma victory threshold reached in match {} (winner team={}); triggering phase transition.",
            match.id,
            winner,
        )

        when (val outcome = switchPhase.execute(match.id, MatchStateMachine.TransitionTrigger.ScoreThresholdReached)) {
            is Result.Success -> log.debug(
                "SwitchPhase -> {} in match {}.",
                outcome.value,
                match.id,
            )

            is Result.Failure -> log.debug(
                "SwitchPhase ScoreThresholdReached for match {} failed ({}); likely already transitioned.",
                match.id,
                outcome.error,
            )
        }
    }
}
