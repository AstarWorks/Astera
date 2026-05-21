package com.astarworks.astera.match.application.usecase.matchmaking

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.application.usecase.match.StartMatchError
import com.astarworks.astera.match.domain.matchmaking.MatchFormation
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.domain.matchmaking.QueueError

/**
 * Wave 5w / ADR-0089 δ — bridge between [IMatchmaker] and Wave 5c
 * `StartMatchUseCase`.
 *
 * Pulls a [MatchFormation] for the requested [MatchmakingMode], starts a
 * match on the configured stage, and entries each formation player on their
 * formation team. The Wave 5c `MatchStarted` → `MatchSpawnUseCase` listener
 * (wired in `AsteraMatchPlugin.kt`) then teleports them.
 *
 * **Idempotency**: `tryFormMatch` is consume-on-success, so the second call
 * (which returns `Success(null)`) is the path that triggers "no formation
 * yet, do nothing".
 */
public class MatchFoundUseCase(
    private val matchmaker: IMatchmaker,
    private val lifecycle: IMatchLifecycle,
    @Suppress("unused") private val eventBus: IDomainEventBus,
    private val defaultStageProvider: (MatchmakingMode) -> StageId,
) {

    /**
     * Try to start one match. Returns:
     *  - `Result.Success(matchId)` — a formation was found AND
     *    `StartMatchUseCase` succeeded; `entry` was called for every player.
     *  - `Result.Success(null)` — no formation available yet (queue too
     *    shallow). No state mutation.
     *  - `Result.Failure(MatchFoundError.QueueFailure)` — matchmaker rejected
     *    the call (e.g. mode closed).
     *  - `Result.Failure(MatchFoundError.StartFailure)` — formation was
     *    consumed but `StartMatchUseCase` rejected the stage (mismatch).
     *    Players are re-queued is NOT performed in Phase 1 PoC; the operator
     *    is alerted via the [StartMatchError] message key.
     */
    public fun execute(mode: MatchmakingMode): Result<MatchId?, MatchFoundError> {
        val formationResult = matchmaker.tryFormMatch(mode)
        when (formationResult) {
            is Result.Failure -> return Result.Failure(MatchFoundError.QueueFailure(formationResult.error))
            is Result.Success -> {
                val formation = formationResult.value ?: return Result.Success(null)
                val stageId = defaultStageProvider(mode)
                return startWithFormation(formation, stageId)
            }
        }
    }

    private fun startWithFormation(
        formation: MatchFormation,
        stageId: StageId,
    ): Result<MatchId?, MatchFoundError> {
        val startResult = lifecycle.start(stageId)
        val matchId = when (startResult) {
            is Result.Success -> startResult.value
            is Result.Failure -> return Result.Failure(MatchFoundError.StartFailure(startResult.error))
        }
        // Entry every formation player on their formation team. The teamId
        // mapping is positional: teams[0] = red, teams[1] = blue (matches
        // StartMatchUseCase default). Phase 2 generalises to N-team modes.
        val teamIds = listOf(
            com.astarworks.astera.core.domain.match.TeamId("red"),
            com.astarworks.astera.core.domain.match.TeamId("blue"),
        )
        formation.teams.forEachIndexed { idx, team ->
            val teamId = teamIds.getOrNull(idx) ?: return@forEachIndexed
            for (playerId in team) {
                lifecycle.entry(matchId, playerId, teamId)
            }
        }
        return Result.Success(matchId)
    }
}

/** Sealed failure modes for [MatchFoundUseCase.execute]. */
public sealed class MatchFoundError {
    public abstract val messageKey: MessageKey

    /** Matchmaker rejected the request (mode closed, etc.). */
    public data class QueueFailure(val cause: QueueError) : MatchFoundError() {
        override val messageKey: MessageKey = MessageKey("astera.match.matchmaking.queue-failure")
    }

    /** Formation was consumed but match start failed (stage mismatch). */
    public data class StartFailure(val cause: StartMatchError) : MatchFoundError() {
        override val messageKey: MessageKey = cause.messageKey
    }
}
