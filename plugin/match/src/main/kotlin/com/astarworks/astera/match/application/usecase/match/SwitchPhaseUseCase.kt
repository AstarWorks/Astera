package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.match.domain.event.PhaseChanged
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.match.domain.match.MatchStateMachine
import com.astarworks.astera.match.domain.match.MatchTransitionError

/**
 * Admin / scheduler override to advance a match's phase via a [MatchStateMachine.TransitionTrigger].
 *
 * Publishes [PhaseChanged] on success.
 */
public class SwitchPhaseUseCase(
    private val matches: IMatchRepository,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
) {

    public fun execute(
        matchId: MatchId,
        trigger: MatchStateMachine.TransitionTrigger,
    ): Result<MatchPhase, SwitchPhaseError> {
        val match = matches.findById(matchId)
            ?: return Result.failure(SwitchPhaseError.MatchNotFound(matchId))

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
}
