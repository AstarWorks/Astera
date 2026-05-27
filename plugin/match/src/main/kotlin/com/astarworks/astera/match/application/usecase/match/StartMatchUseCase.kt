package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.domain.event.MatchStarted
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
 *
 * # Mode-aware team formation (Wave 5wireup, ADR-0116 §G16)
 *
 * Optional [execute] parameters let callers select the match's mode:
 *
 *  - `modeKey = null` (default) → 2-team formation (`red` / `blue`). Phase 1
 *    Norma + every existing test path uses this.
 *  - `modeKey = "annihilation"` → 3-team formation (`red` / `blue` /
 *    `yellow`). The wiring layer in `AsteraMatchPlugin` reads the modeKey
 *    off [MatchStarted] to dispatch `AnnihilationGameMode.onMatchStarted`
 *    and seed `GameModeContext.scoringState["annihilation.teams"]` with the
 *    team ids.
 *
 * `teamIds` is exposed for callers that want an explicit team set (testing,
 * Wave 6+ custom mode wiring). When `null`, the team list is derived from
 * the modeKey:
 *
 *  - `"annihilation"` → [DEFAULT_ANNIHILATION_TEAMS] = `[red, blue, yellow]`
 *  - everything else → [DEFAULT_TEAMS] = `[red, blue]`
 *
 * `MessageKey` lookups for team names use a `astera.match.team.<id>`
 * pattern so new team ids slot into the i18n catalogue without code
 * changes.
 */
public class StartMatchUseCase(
    private val stages: IStageRegistry,
    private val matches: IMatchRepository,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
) {

    public fun execute(
        stageId: StageId,
        modeKey: String? = null,
        teamIds: List<TeamId>? = null,
    ): Result<MatchId, StartMatchError> {
        val stage = stages.find(stageId)
            ?: return Result.failure(StartMatchError.StageNotFound(stageId))

        val activeForStage = matches.findActiveMatches()
            .any { it.stageId == stageId }
        if (activeForStage) {
            return Result.failure(StartMatchError.AlreadyActive(stageId))
        }

        val resolvedTeamIds: List<TeamId> = teamIds
            ?: defaultTeamIdsFor(modeKey)
        require(resolvedTeamIds.isNotEmpty()) { "StartMatchUseCase requires at least one team id" }

        val matchId = MatchId("match-${UUID.randomUUID().toString().take(8)}")
        val initialTeams = resolvedTeamIds.map { teamId ->
            Team(
                id = teamId,
                displayNameKey = MessageKey("astera.match.team.${teamId.value}"),
                colorKey = MessageKey("astera.match.team.${teamId.value}.color"),
                members = emptySet(),
            )
        }
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
                modeKey = modeKey,
            ),
        )

        return Result.success(matchId)
    }

    private fun defaultTeamIdsFor(modeKey: String?): List<TeamId> = when (modeKey) {
        MODE_KEY_ANNIHILATION -> DEFAULT_ANNIHILATION_TEAMS
        else -> DEFAULT_TEAMS
    }

    public companion object {
        public const val MODE_KEY_ANNIHILATION: String = "annihilation"

        /** Two-team default used by every Phase 1 mode except Annihilation. */
        public val DEFAULT_TEAMS: List<TeamId> = listOf(TeamId("red"), TeamId("blue"))

        /** Three-team default required by Annihilation's nexus topology. */
        public val DEFAULT_ANNIHILATION_TEAMS: List<TeamId> =
            listOf(TeamId("red"), TeamId("blue"), TeamId("yellow"))
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
