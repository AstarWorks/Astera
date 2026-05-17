package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IStageRepository
import com.astarworks.astera.match.domain.event.MatchStarted
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.match.domain.match.MatchStateMachine
import com.astarworks.astera.match.domain.match.MatchTransitionError
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import java.util.UUID

/**
 * Creates a new match for [stageId] and transitions it from WAITING → COUNTDOWN.
 *
 * Publishes [MatchStarted] on success.
 */
public class StartMatchUseCase(
    private val stages: IStageRepository,
    private val matches: IMatchRepository,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
) {

    public fun execute(stageId: StageId): Result<MatchId, StartMatchError> {
        val stage = stages.findById(stageId)
            ?: return Result.failure(StartMatchError.StageNotFound(stageId))

        val activeForStage = matches.findActiveMatches()
            .any { it.stageId == stageId }
        if (activeForStage) {
            return Result.failure(StartMatchError.AlreadyActive(stageId))
        }

        val matchId = MatchId("match-${UUID.randomUUID().toString().take(8)}")
        val initialTeams = listOf(
            Team(
                id = TeamId("red"),
                displayNameKey = MessageKey("astera.match.team.red"),
                colorKey = MessageKey("astera.match.team.red.color"),
                members = emptySet(),
            ),
            Team(
                id = TeamId("blue"),
                displayNameKey = MessageKey("astera.match.team.blue"),
                colorKey = MessageKey("astera.match.team.blue.color"),
                members = emptySet(),
            ),
        )
        val currentTick = scheduler.currentTick()
        val match = Match(
            id = matchId,
            phase = MatchPhase.WAITING,
            teams = initialTeams,
            score = Score(initialTeams.associate { it.id to 0 }),
            stageId = stage.id,
            startedAtTick = currentTick,
        )

        val transitionResult = MatchStateMachine.transition(
            from = MatchPhase.WAITING,
            trigger = MatchStateMachine.TransitionTrigger.Start,
        )
        val nextPhase = when (transitionResult) {
            is Result.Success -> transitionResult.value
            is Result.Failure -> return Result.failure(
                StartMatchError.TransitionFailed(transitionResult.error),
            )
        }

        val updatedMatch = match.withPhase(nextPhase)
        matches.save(updatedMatch)

        eventBus.publish(
            MatchStarted(
                matchId = matchId,
                stageId = stageId,
                atTick = currentTick,
            ),
        )

        return Result.success(matchId)
    }
}

/** Expected failure modes of [StartMatchUseCase.execute]. */
public sealed class StartMatchError {
    public abstract val messageKey: MessageKey

    public data class StageNotFound(val stageId: StageId) : StartMatchError() {
        override val messageKey: MessageKey = MessageKey("astera.match.error.stage-not-found")
    }

    public data class AlreadyActive(val stageId: StageId) : StartMatchError() {
        override val messageKey: MessageKey = MessageKey("astera.match.error.already-active")
    }

    public data class TransitionFailed(val cause: MatchTransitionError) : StartMatchError() {
        override val messageKey: MessageKey = cause.messageKey
    }
}
