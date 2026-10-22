package com.astarworks.astera.match.application.usecase.matchmaking

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.application.port.outbound.matchmaking.IQueueNotifier
import com.astarworks.astera.match.application.usecase.match.StartMatchError
import com.astarworks.astera.match.domain.matchmaking.MatchFormation
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.domain.matchmaking.QueueError

/** Where and how a queue lane plays: the stage and `MatchStarted.modeKey` (`null` = legacy Norma). */
public data class MatchPlan(val stageId: StageId, val modeKey: String?)

/**
 * Bridge between [IMatchmaker] and the match lifecycle (Wave 5w / ADR-0089 δ,
 * completed in WP8).
 *
 * Pulls a [MatchFormation] for a [MatchmakingMode], starts a match on the lane's
 * stage, and enters every formation player on their formation team. Entry
 * teleports each player to a stage spawn (`EntryGameUseCase`).
 *
 * - **Idempotent**: `tryFormMatch` is consume-on-success; an empty/short queue
 *   returns `Success(null)` without mutating anything.
 * - **Stage busy**: a stage hosts one match at a time; while it is busy no
 *   formation is consumed ([isStageBusy]).
 * - **Start failure**: the consumed players are put back in the queue so a bad
 *   stage config does not silently drop them.
 */
public class MatchFoundUseCase(
    private val matchmaker: IMatchmaker,
    private val lifecycle: IMatchLifecycle,
    private val matches: IMatchRepository,
    private val notifier: IQueueNotifier,
    private val planFor: (MatchmakingMode) -> MatchPlan,
) : MatchFormer {

    /** `true` while a non-ended match already occupies [mode]'s stage. */
    override fun isStageBusy(mode: MatchmakingMode): Boolean {
        val stageId = planFor(mode).stageId
        return matches.findActiveMatches().any { it.stageId == stageId }
    }

    /**
     * Try to start one match. Returns:
     *  - `Success(matchId)`: a formation was found and the match started;
     *    every player was entered (and teleported) on a team.
     *  - `Success(null)`: nothing to do (queue too shallow, or stage busy).
     *  - `Failure(QueueFailure)`: the matchmaker rejected the call (mode closed...).
     *  - `Failure(StartFailure)`: the match could not start; players were re-queued.
     */
    override fun execute(mode: MatchmakingMode): Result<MatchId?, MatchFoundError> {
        if (isStageBusy(mode)) return Result.Success(null)
        val formation = when (val formationResult = matchmaker.tryFormMatch(mode)) {
            is Result.Failure -> return Result.Failure(MatchFoundError.QueueFailure(formationResult.error))
            is Result.Success -> formationResult.value ?: return Result.Success(null)
        }
        return startWithFormation(formation, planFor(mode))
    }

    private fun startWithFormation(
        formation: MatchFormation,
        plan: MatchPlan,
    ): Result<MatchId?, MatchFoundError> {
        val matchId = when (val startResult = lifecycle.start(plan.stageId, plan.modeKey)) {
            is Result.Success -> startResult.value
            is Result.Failure -> {
                requeue(formation)
                return Result.Failure(MatchFoundError.StartFailure(startResult.error))
            }
        }
        // Formation team i maps onto the match's i-th team (red, blue, ...);
        // surplus formation teams fold back so nobody is dropped.
        val teamIds: List<TeamId> = matches.findById(matchId)?.teams?.map { it.id }.orEmpty()
        formation.teams.forEachIndexed { idx, team ->
            val teamId = if (teamIds.isEmpty()) null else teamIds[idx % teamIds.size]
            for (playerId in team) {
                lifecycle.entry(matchId, playerId, teamId)
                notifier.notify(
                    playerId,
                    MessageKey("astera.match.matchmaking.match-found"),
                    mapOf("team" to (teamId?.value ?: ""), "stage" to plan.stageId.value),
                )
            }
        }
        return Result.Success(matchId)
    }

    private fun requeue(formation: MatchFormation) {
        for (playerId in formation.players) {
            matchmaker.enqueue(playerId, formation.mode)
            notifier.notify(playerId, MessageKey("astera.match.matchmaking.start-failed"), emptyMap())
        }
    }
}

/**
 * Forms matches out of the queues. [MatchFoundUseCase] starts them on this
 * server; `RemoteMatchFormer` hands them to the game server (ADR-0179).
 */
public interface MatchFormer {
    /** `true` while [mode]'s lane cannot take another match. */
    public fun isStageBusy(mode: MatchmakingMode): Boolean

    /** Try to form one match for [mode]. `Success(null)` = nothing to do. */
    public fun execute(mode: MatchmakingMode): Result<MatchId?, MatchFoundError>
}

/** Sealed failure modes for [MatchFoundUseCase.execute]. */
public sealed class MatchFoundError {
    public abstract val messageKey: MessageKey

    /** Matchmaker rejected the request (mode closed, etc.). */
    public data class QueueFailure(val cause: QueueError) : MatchFoundError() {
        override val messageKey: MessageKey = MessageKey("astera.match.matchmaking.queue-failure")
    }

    /** Formation was consumed but match start failed (stage mismatch); players were re-queued. */
    public data class StartFailure(val cause: StartMatchError) : MatchFoundError() {
        override val messageKey: MessageKey = cause.messageKey
    }

    /** The match could not be handed to the game server; players were re-queued. */
    public data class DispatchFailure(val reason: String) : MatchFoundError() {
        override val messageKey: MessageKey = MessageKey("astera.match.matchmaking.dispatch-failure")
    }
}
