package com.astarworks.astera.match.application.eventbus.listeners.victory

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.AnnihilationPhaseTransitioned
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.TeamEliminated
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.nexus.NexusPhase
import com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationMatchTracker
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import com.astarworks.astera.match.domain.gamemode.annihilation.WinConditionRules
import org.slf4j.LoggerFactory

/**
 * End-of-match driver for Annihilation (Wave 5game-complete, ADR-0121 §A).
 *
 * Before this wave, `AnnihilationGameMode.onTick` consulted
 * `AnnihilationMatchTracker.evaluateWinCondition` but did nothing with
 * the result — the audit gap recorded the win-condition path as
 * "read-only". Matches never actually ended via the Annihilation
 * outcome; only `/astera match phase` admin commands could close them.
 *
 * # Two trigger paths
 *
 *  1. **TeamEliminated** — fires from the tracker when a team's Tier 3
 *     pillar falls. After the event, the listener re-asks the tracker
 *     for the current outcome:
 *     - [WinConditionRules.Outcome.Winner] → call
 *       [IMatchLifecycle.end] with [EndReason.TeamVictory] (winner carried
 *       into `MatchEnded.winningTeam`).
 *     - [WinConditionRules.Outcome.Draw] → call `end` with
 *       `TeamVictory(null)` (`MatchEnded.winningTeam = null`).
 *     - [WinConditionRules.Outcome.InProgress] → drop (a single team
 *       wipe out of three may leave two survivors fighting on).
 *  2. **AnnihilationPhaseTransitioned to Phase4SuddenDeath** — the
 *     sudden-death timer becomes authoritative. The listener schedules
 *     a one-shot tick task at `now + suddenDeathTicks`. At expiry it
 *     re-asks the tracker; whatever the outcome says, the match ends.
 *     Reason is [EndReason.TeamVictory] (winner null on a draw).
 *
 * # Idempotency
 *
 * `IMatchLifecycle.end` for an already-ended match returns a
 * [com.astarworks.astera.match.application.usecase.match.EndMatchError]
 * — the listener logs at DEBUG and continues. A second `TeamEliminated`
 * arriving after the first call therefore can't double-end.
 *
 * # Why not extend the tracker?
 *
 * The tracker is a pure-ish state machine — owning a `IMatchLifecycle`
 * port would force it to depend on the inbound facade and the
 * application would lose its compile-time ability to swap the lifecycle
 * impl. Keeping the "decide → act" split in this listener mirrors how
 * `NormaVictoryConditionEvaluatorListener` is structured.
 */
public class AnnihilationWinConditionListener(
    private val tracker: AnnihilationMatchTracker,
    private val lifecycle: IMatchLifecycle,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
    private val suddenDeathTicks: Long = AnnihilationConfig.DEFAULT.suddenDeathTicks,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Subscribe both event paths. Returns the two subscription handles. */
    public fun subscribe(): List<IDomainEventBus.Subscription> = listOf(
        eventBus.subscribe(TeamEliminated::class) { handleTeamEliminated(it) },
        eventBus.subscribe(AnnihilationPhaseTransitioned::class) { handlePhaseTransition(it) },
    )

    public fun handleTeamEliminated(event: TeamEliminated) {
        val outcome = tracker.evaluateWinCondition(event.matchId, event.atTick)
        when (outcome) {
            is WinConditionRules.Outcome.Winner ->
                endMatch(event.matchId, EndReason.TeamVictory(outcome.team), "winner=${outcome.team}")
            WinConditionRules.Outcome.Draw -> endMatch(event.matchId, EndReason.TeamVictory(null), "draw")
            WinConditionRules.Outcome.InProgress -> Unit // wait for next elimination / SD
        }
    }

    public fun handlePhaseTransition(event: AnnihilationPhaseTransitioned) {
        if (event.newPhase != NexusPhase.Phase4SuddenDeath) return
        // One-shot timer: at sudden-death expiry, ask the tracker for the
        // final outcome and end the match regardless of who wins / draw.
        scheduler.runLater(ticks(suddenDeathTicks)) {
            val outcome = tracker.evaluateWinCondition(event.matchId, scheduler.currentTick())
            // InProgress at SD timer expiry is unexpected (the rules say SD must
            // resolve at elapsed >= suddenDeathTicks; a single-team playtest is the
            // one legitimate case). Still end the match - hanging in SD is worse -
            // without a winner.
            val winner = (outcome as? WinConditionRules.Outcome.Winner)?.team
            endMatch(event.matchId, EndReason.TeamVictory(winner), "sudden-death outcome=$outcome")
        }
    }

    private fun endMatch(matchId: MatchId, reason: EndReason, debugTag: String) {
        when (val result = lifecycle.end(matchId, reason)) {
            is Result.Success -> log.info(
                "AnnihilationWinCondition ended match {} (reason={}; {}).",
                matchId, reason, debugTag,
            )

            is Result.Failure -> log.debug(
                "AnnihilationWinCondition end-call failed for match {} ({}); likely already ended.",
                matchId, result.error,
            )
        }
    }
}
