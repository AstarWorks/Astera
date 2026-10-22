package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.match.application.port.outbound.IMatchModeRegistry
import com.astarworks.astera.match.domain.event.PhaseChanged
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.match.domain.match.MatchMinPlayerPolicy
import com.astarworks.astera.match.domain.match.MatchStateMachine
import com.astarworks.astera.match.domain.match.MatchTransitionError

/**
 * Admin / scheduler override to advance a match's phase via a [MatchStateMachine.TransitionTrigger].
 *
 * Publishes [PhaseChanged] on success.
 *
 * # Min-player guard (Wave 5game-complete, ADR-0121 §F)
 *
 * On a [MatchStateMachine.TransitionTrigger.CountdownComplete] trigger
 * (COUNTDOWN → ACTIVE) the use case consults
 * [MatchMinPlayerPolicy] before applying the transition. If the match's
 * roster is below the mode's floor (Norma: 2, Annihilation: 3, Bet 1v1: 2)
 * the transition is rejected with [SwitchPhaseError.InsufficientPlayers]
 * so the caller can keep the match in COUNTDOWN (queue still filling)
 * instead of crashing into an empty ACTIVE phase.
 *
 * `modeKey` resolution goes through [IMatchModeRegistry]; the registry
 * is populated by `AsteraMatchPlugin`'s `MatchStarted` subscriber. If the
 * registry has no entry for [matchId] (unit-test paths that never
 * publish `MatchStarted`, or persistence-restart corner case) the policy
 * defaults to the Norma 2-player floor — same intent as the registry's
 * `null` modeKey contract.
 */
public class SwitchPhaseUseCase(
    private val matches: IMatchRepository,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
    private val modeRegistry: IMatchModeRegistry? = null,
    /**
     * Roster floor for COUNTDOWN to ACTIVE per modeKey (WP8: the queue lane's
     * `min_players`, so a 1-player playtest match can start). Defaults to the
     * mode policy.
     */
    private val minPlayersFor: (String?) -> Int = MatchMinPlayerPolicy::requirementFor,
    /**
     * F1-D (ADR-0182): matches an admin forced past the roster floor (panel
     * "force start now" / "next phase"). Waived matches skip the min-player
     * guard on COUNTDOWN to ACTIVE.
     */
    private val minPlayersWaived: (MatchId) -> Boolean = { false },
) {

    public fun execute(
        matchId: MatchId,
        trigger: MatchStateMachine.TransitionTrigger,
    ): Result<MatchPhase, SwitchPhaseError> {
        val match = matches.findById(matchId)
            ?: return Result.failure(SwitchPhaseError.MatchNotFound(matchId))

        if (trigger == MatchStateMachine.TransitionTrigger.CountdownComplete && !minPlayersWaived(matchId)) {
            val modeKey = modeRegistry?.modeKeyOf(matchId)
            when (val gate = MatchMinPlayerPolicy.check(minPlayersFor(modeKey), match)) {
                MatchMinPlayerPolicy.Outcome.Ok -> Unit
                is MatchMinPlayerPolicy.Outcome.Insufficient ->
                    return Result.failure(
                        SwitchPhaseError.InsufficientPlayers(
                            matchId = matchId,
                            required = gate.required,
                            actual = gate.actual,
                        ),
                    )
            }
        }

        val old = match.phase
        val transitionResult = MatchStateMachine.transition(old, trigger)
        val next = when (transitionResult) {
            is Result.Success -> transitionResult.value
            is Result.Failure -> return Result.failure(
                SwitchPhaseError.TransitionFailed(transitionResult.error),
            )
        }

        val updated = match.withPhase(next)
        matches.save(updated)

        eventBus.publish(
            PhaseChanged(
                matchId = matchId,
                from = old,
                to = next,
                atTick = scheduler.currentTick(),
            ),
        )

        return Result.success(next)
    }
}

/** Expected failure modes of [SwitchPhaseUseCase.execute]. */
public sealed class SwitchPhaseError {
    public abstract val messageKey: MessageKey

    public data class MatchNotFound(val matchId: MatchId) : SwitchPhaseError() {
        override val messageKey: MessageKey = MessageKey("astera.match.error.match-not-found")
    }

    public data class TransitionFailed(val cause: MatchTransitionError) : SwitchPhaseError() {
        override val messageKey: MessageKey = cause.messageKey
    }

    /**
     * Wave 5game-complete (ADR-0121 §F): COUNTDOWN→ACTIVE rejected
     * because the roster is below the mode's minimum.
     */
    public data class InsufficientPlayers(
        val matchId: MatchId,
        val required: Int,
        val actual: Int,
    ) : SwitchPhaseError() {
        override val messageKey: MessageKey = MessageKey("astera.match.error.insufficient-players")
    }
}
