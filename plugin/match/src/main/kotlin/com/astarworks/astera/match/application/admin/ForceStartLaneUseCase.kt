package com.astarworks.astera.match.application.admin

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.application.usecase.matchmaking.MatchFoundError
import com.astarworks.astera.match.application.usecase.matchmaking.MatchFormer
import com.astarworks.astera.match.domain.match.MatchStateMachine
import com.astarworks.astera.match.domain.matchmaking.QueueError
import com.astarworks.astera.match.domain.matchmaking.QueueSettings

/** Why an admin "force start" did not produce a running match. */
public sealed class ForceStartError {
    public abstract val messageKey: MessageKey

    public data class UnknownLane(val laneKey: String) : ForceStartError() {
        override val messageKey: MessageKey = MessageKey("astera.admin.panel.match.err.unknown-lane")
    }

    public data class NobodyQueued(val laneKey: String) : ForceStartError() {
        override val messageKey: MessageKey = MessageKey("astera.admin.panel.match.err.nobody-queued")
    }

    /** The lane's stage already hosts a running match (one match per stage). */
    public data class StageBusy(val laneKey: String) : ForceStartError() {
        override val messageKey: MessageKey = MessageKey("astera.admin.panel.match.err.stage-busy")
    }

    /** The queue refused the player (lane closed, party too large, ...). */
    public data class Queue(val cause: QueueError) : ForceStartError() {
        override val messageKey: MessageKey = MessageKey("astera.match.matchmaking.queue-failure")
    }

    /** The formation was consumed but the match could not start (players are re-queued). */
    public data class Start(val cause: MatchFoundError) : ForceStartError() {
        override val messageKey: MessageKey = cause.messageKey
    }

    /** The match started but could not be pushed to ACTIVE. */
    public data class Phase(override val messageKey: MessageKey) : ForceStartError()
}

/**
 * Admin-panel queue actions (F1-D, ADR-0183):
 *
 *  - [forceStart] — start the lane's match **now** with whoever is queued,
 *    ignoring the lane's min-player floor and both countdowns.
 *  - [startWithOnly] — queue [PlayerId] in the lane (leaving any other lane)
 *    and start a match with just that player; everyone else stays queued.
 *
 * Both go through the registered [MatchFormer] (local `MatchFoundUseCase`, or
 * `RemoteMatchFormer` on a lobby server, which hands the formation to the game
 * server like a normal one). A local match then skips the match countdown, so
 * the result is an ACTIVE match. They are application use cases, not
 * commands, so the panel adapts if the proxy split changes how lanes work.
 */
public class ForceStartLaneUseCase(
    private val matchmaker: IMatchmaker,
    private val matchFound: MatchFormer,
    private val matches: IMatchRepository,
    private val lifecycle: IMatchLifecycle,
    private val waivers: MatchMinPlayerWaivers,
    private val settings: () -> QueueSettings,
) {

    public fun forceStart(laneKey: String): Result<MatchId, ForceStartError> {
        val lane = settings().lanes[laneKey] ?: return Result.failure(ForceStartError.UnknownLane(laneKey))
        if (matchmaker.pendingByMode(lane.mode).isEmpty()) {
            return Result.failure(ForceStartError.NobodyQueued(laneKey))
        }
        if (matchFound.isStageBusy(lane.mode)) return Result.failure(ForceStartError.StageBusy(laneKey))
        return form(laneKey, matchFound.execute(lane.mode, minPlayers = 1))
    }

    public fun startWithOnly(laneKey: String, player: PlayerId): Result<MatchId, ForceStartError> {
        val lane = settings().lanes[laneKey] ?: return Result.failure(ForceStartError.UnknownLane(laneKey))
        if (matchFound.isStageBusy(lane.mode)) return Result.failure(ForceStartError.StageBusy(laneKey))
        val existing = matchmaker.queueStatus(player)
        if (existing != null && existing.mode != lane.mode) matchmaker.dequeue(player)
        if (existing == null || existing.mode != lane.mode) {
            when (val q = matchmaker.enqueue(player, lane.mode, partyId = null)) {
                is Result.Success -> Unit
                is Result.Failure -> return Result.failure(ForceStartError.Queue(q.error))
            }
        }
        return form(laneKey, matchFound.execute(lane.mode, minPlayers = 1, only = setOf(player)))
    }

    private fun form(
        laneKey: String,
        formed: Result<MatchId?, MatchFoundError>,
    ): Result<MatchId, ForceStartError> {
        val matchId = when (formed) {
            is Result.Failure -> return Result.failure(ForceStartError.Start(formed.error))
            is Result.Success -> formed.value ?: return Result.failure(ForceStartError.NobodyQueued(laneKey))
        }
        // Handed to the game server (lobby role): the id is an assignment id, there is
        // no local match to push; the game server starts it through its own queue floor.
        if (matches.findById(matchId) == null) return Result.success(matchId)
        waivers.waive(matchId)
        return when (val r = lifecycle.switchPhase(matchId, MatchStateMachine.TransitionTrigger.CountdownComplete)) {
            is Result.Success -> Result.success(matchId)
            is Result.Failure -> Result.failure(ForceStartError.Phase(r.error.messageKey))
        }
    }
}

/** [MatchFormer] for servers without queue lanes (GAME role): never forms anything. */
public object NoLanesFormer : MatchFormer {
    override fun isStageBusy(mode: com.astarworks.astera.match.domain.matchmaking.MatchmakingMode): Boolean = false

    override fun execute(
        mode: com.astarworks.astera.match.domain.matchmaking.MatchmakingMode,
        minPlayers: Int?,
        only: Set<PlayerId>?,
    ): Result<MatchId?, com.astarworks.astera.match.application.usecase.matchmaking.MatchFoundError> =
        Result.success(null)
}
