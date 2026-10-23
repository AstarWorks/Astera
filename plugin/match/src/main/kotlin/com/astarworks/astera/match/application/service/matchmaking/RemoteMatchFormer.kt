package com.astarworks.astera.match.application.service.matchmaking

import com.astarworks.astera.core.application.port.outbound.IProxyMessenger
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IServerLiveness
import com.astarworks.astera.core.application.port.outbound.IServerTransferGateway
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ProxyChannel
import com.astarworks.astera.core.domain.proxy.TransferReason
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.application.port.outbound.matchmaking.IQueueNotifier
import com.astarworks.astera.match.application.usecase.matchmaking.MatchFormer
import com.astarworks.astera.match.application.usecase.matchmaking.MatchFoundError
import com.astarworks.astera.match.application.usecase.matchmaking.MatchPlan
import com.astarworks.astera.match.domain.matchmaking.AssignmentStatus
import com.astarworks.astera.match.domain.matchmaking.MatchAssignment
import com.astarworks.astera.match.domain.matchmaking.MatchFormation
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Lobby-side match former (ADR-0179): instead of starting the match here it
 * hands the formed teams to the game server.
 *
 * ```
 * lobby                                   game
 *   |-- MatchAssignment (MATCH_FOUND) ---->|
 *   |<-------- Accepted (MATCH_STATUS) ----|   else Rejected / ack timeout -> players re-queued
 *   |== transfer every player ============>|
 *   |<-------- Ended (MATCH_STATUS) -------|   game server is free again
 * ```
 *
 * The game server runs one match at a time, and is only assigned while its
 * heartbeat is alive (ADR-0187: 5 s beat, down after 15 s); while it is down the
 * queue is held and the queued players told, instead of assigning into the void.
 * So while an assignment is in flight (or until its [CrossServerSettings.matchLeaseSeconds] lease runs out)
 * [isStageBusy] holds the queue, and the scheduler tells the queued players
 * "the arena is in use".
 */
public class RemoteMatchFormer(
    private val matchmaker: IMatchmaker,
    private val messenger: IProxyMessenger,
    private val transfers: IServerTransferGateway,
    private val liveness: IServerLiveness,
    private val notifier: IQueueNotifier,
    private val scheduler: IScheduler,
    private val settings: CrossServerSettings,
    private val planFor: (MatchmakingMode) -> MatchPlan,
) : MatchFormer {

    private val log = LoggerFactory.getLogger(javaClass)

    private class InFlight(
        val assignment: MatchAssignment,
        val formation: MatchFormation,
        var accepted: Boolean,
        var leaseUntilTick: Long,
        var ackTimeout: IScheduler.TaskHandle?,
    )

    private var inFlight: InFlight? = null

    /** Listens for the game server's answers. The caller cancels the subscription on shutdown. */
    public fun start(): IProxyMessenger.Subscription =
        messenger.subscribe(ProxyChannel.MATCH_STATUS) { message ->
            val status = MatchAssignmentCodec.decodeStatus(message.payload)
            if (status == null) log.warn("Dropping malformed match status from {}", message.fromServer) else onStatus(status)
        }

    override fun holdNotice(mode: MatchmakingMode): String =
        if (liveness.isUp(settings.gameServer)) "stage-busy" else "server-down"

    override fun isStageBusy(mode: MatchmakingMode): Boolean {
        if (!liveness.isUp(settings.gameServer)) return true
        val current = inFlight ?: return false
        if (scheduler.currentTick() < current.leaseUntilTick) return true
        log.warn("Game server lease for assignment {} expired without an Ended answer; releasing.", current.assignment.id)
        inFlight = null
        return false
    }

    override fun execute(
        mode: MatchmakingMode,
        minPlayers: Int?,
        only: Set<PlayerId>?,
    ): Result<MatchId?, MatchFoundError> {
        if (isStageBusy(mode)) return Result.Success(null)
        val formation = when (val formed = matchmaker.tryFormMatch(mode, minPlayers, only)) {
            is Result.Failure -> return Result.Failure(MatchFoundError.QueueFailure(formed.error))
            is Result.Success -> formed.value ?: return Result.Success(null)
        }
        val plan = planFor(mode)
        val assignment = MatchAssignment(
            id = MatchId("a-${UUID.randomUUID()}"),
            stageId = plan.stageId,
            modeKey = plan.modeKey,
            teams = formation.teams.map { it.toList() },
            // An explicit roster override is the admin force start: the game server must not re-apply its floor.
            forced = minPlayers != null,
        )
        // Register before sending: a fast game server may answer before sendMessage returns.
        val lease = scheduler.currentTick() + settings.matchLeaseSeconds * TICKS_PER_SECOND
        val flight = InFlight(assignment, formation, accepted = false, leaseUntilTick = lease, ackTimeout = null)
        inFlight = flight
        val sent = messenger.sendMessage(settings.gameServer, ProxyChannel.MATCH_FOUND, MatchAssignmentCodec.encode(assignment))
        if (sent is Result.Failure) {
            inFlight = null
            requeue(formation, "dispatch-failure")
            return Result.Failure(MatchFoundError.DispatchFailure("send failed: ${sent.error}"))
        }
        if (!flight.accepted) {
            flight.ackTimeout = scheduler.runLater(ticks(settings.ackTimeoutSeconds * TICKS_PER_SECOND)) { onAckTimeout(assignment.id) }
        }
        log.info("Assignment {} sent to {} ({} players)", assignment.id, settings.gameServer, formation.players.size)
        return Result.Success(assignment.id)
    }

    private fun onStatus(status: AssignmentStatus) {
        val flight = inFlight?.takeIf { it.assignment.id == status.assignmentId } ?: return
        when (status) {
            is AssignmentStatus.Accepted -> {
                flight.ackTimeout?.cancel()
                flight.accepted = true
                log.info(
                    "Assignment {} accepted by {}; transferring {} players",
                    status.assignmentId, settings.gameServer, flight.formation.players.size,
                )
                for (player in flight.formation.players) {
                    notifier.notify(player, MessageKey("astera.match.matchmaking.transferring"), emptyMap())
                    when (val moved = transfers.transfer(player, settings.gameServer, TransferReason.MatchFound)) {
                        is Result.Failure -> log.warn("Transfer of {} to {} failed: {}", player, settings.gameServer, moved.error)
                        is Result.Success -> log.info("Transfer of {} to {} requested (assignment {})", player, settings.gameServer, status.assignmentId)
                    }
                }
            }
            is AssignmentStatus.Rejected -> {
                flight.ackTimeout?.cancel()
                inFlight = null
                log.warn("Assignment {} rejected by the game server: {}", status.assignmentId, status.reason)
                requeue(flight.formation, "dispatch-failure")
            }
            is AssignmentStatus.Ended -> {
                inFlight = null
                log.info("Assignment {} ended; game server is free.", status.assignmentId)
            }
        }
    }

    private fun onAckTimeout(id: MatchId) {
        val flight = inFlight?.takeIf { it.assignment.id == id && !it.accepted } ?: return
        inFlight = null
        log.warn("Game server did not answer assignment {} in time; re-queueing {} players.", id, flight.formation.players.size)
        requeue(flight.formation, "dispatch-failure")
    }

    private fun requeue(formation: MatchFormation, noticeKey: String) {
        for (player in formation.players) {
            matchmaker.enqueue(player, formation.mode)
            notifier.notify(player, MessageKey("astera.match.matchmaking.$noticeKey"), emptyMap())
        }
    }

    private companion object {
        const val TICKS_PER_SECOND = 20L
    }
}
