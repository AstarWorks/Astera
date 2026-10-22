package com.astarworks.astera.match.application.service.matchmaking

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IProxyMessenger
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IServerTransferGateway
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ProxyChannel
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.core.domain.proxy.TransferReason
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.matchmaking.IQueueNotifier
import com.astarworks.astera.match.domain.matchmaking.AssignmentStatus
import com.astarworks.astera.match.domain.matchmaking.MatchAssignment
import org.slf4j.LoggerFactory

/**
 * Game-server side of the lobby / game hand-off (ADR-0179).
 *
 * 1. A [MatchAssignment] arrives. A busy server answers `Rejected`; otherwise
 *    `Accepted` and the server waits for the assigned players (one assignment
 *    at a time).
 * 2. Assigned players arrive through the proxy ([PlayerJoinedServer]). When all
 *    of them are here, or [CrossServerSettings.arrivalTimeoutSeconds] ran out,
 *    the match starts and every arrived player enters on their team. Players
 *    who never showed up are dropped. Nobody arrived: no match, `Ended`.
 * 3. When the match ends, after [CrossServerSettings.returnDelaySeconds] every
 *    participant is transferred back to the lobby server and `Ended` is sent.
 *
 * Anybody else who connects to a game server (not assigned, or too late) is
 * sent back to the lobby: this server hosts matches only.
 */
public class MatchAssignmentReceiver(
    private val messenger: IProxyMessenger,
    private val transfers: IServerTransferGateway,
    private val lifecycle: IMatchLifecycle,
    private val matches: IMatchRepository,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
    private val notifier: IQueueNotifier,
    private val settings: CrossServerSettings,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private class Session(
        val assignment: MatchAssignment,
        val replyTo: ServerName,
        val arrived: MutableSet<PlayerId> = linkedSetOf(),
        var matchId: MatchId? = null,
        var arrivalTimeout: IScheduler.TaskHandle? = null,
    )

    private var session: Session? = null
    private val handles = mutableListOf<() -> Unit>()

    /** Subscribes to the assignment channel and the events it reacts to. Call [stop] on shutdown. */
    public fun start() {
        val assignments = messenger.subscribe(ProxyChannel.MATCH_FOUND) { message ->
            val assignment = MatchAssignmentCodec.decodeAssignment(message.payload)
            if (assignment == null) log.warn("Dropping malformed match assignment from {}", message.fromServer) else onAssignment(assignment, message.fromServer)
        }
        val joined = eventBus.subscribe(PlayerJoinedServer::class) { onPlayerJoined(it.playerId) }
        val ended = eventBus.subscribe(MatchEnded::class) { onMatchEnded(it.matchId) }
        handles += { assignments.cancel() }
        handles += { joined.cancel() }
        handles += { ended.cancel() }
    }

    public fun stop() {
        handles.forEach { runCatching(it) }
        handles.clear()
        session?.arrivalTimeout?.cancel()
        session = null
    }

    private fun onAssignment(assignment: MatchAssignment, from: ServerName) {
        if (session != null || matches.findActiveMatches().isNotEmpty()) {
            reply(from, AssignmentStatus.Rejected(assignment.id, "busy"))
            return
        }
        val created = Session(assignment, from)
        created.arrivalTimeout = scheduler.runLater(ticks(settings.arrivalTimeoutSeconds * TICKS_PER_SECOND)) { onArrivalTimeout(created) }
        session = created
        reply(from, AssignmentStatus.Accepted(assignment.id))
        log.info("Accepted assignment {} ({} players, stage {})", assignment.id, assignment.players.size, assignment.stageId)
    }

    private fun onPlayerJoined(playerId: PlayerId) {
        val current = session
        if (current == null || current.matchId != null || playerId !in current.assignment.players) {
            bounceIfStray(playerId)
            return
        }
        current.arrived += playerId
        if (current.arrived.size == current.assignment.players.size) startMatch(current)
    }

    private fun onArrivalTimeout(current: Session) {
        if (session !== current || current.matchId != null) return
        val missing = current.assignment.players.size - current.arrived.size
        log.warn("Assignment {}: {} player(s) never arrived; starting without them.", current.assignment.id, missing)
        startMatch(current)
    }

    private fun startMatch(current: Session) {
        current.arrivalTimeout?.cancel()
        if (current.arrived.isEmpty()) {
            log.warn("Assignment {}: nobody arrived; dropping it.", current.assignment.id)
            finish(current)
            return
        }
        val assignment = current.assignment
        val matchId = when (val started = lifecycle.start(assignment.stageId, assignment.modeKey)) {
            is Result.Success -> started.value
            is Result.Failure -> {
                log.warn("Assignment {}: match could not start: {}", assignment.id, started.error)
                current.arrived.forEach { returnToLobby(it) }
                finish(current)
                return
            }
        }
        current.matchId = matchId
        // Assignment team i maps onto the match's i-th team; surplus teams fold back.
        val teamIds = matches.findById(matchId)?.teams?.map { it.id }.orEmpty()
        assignment.teams.forEachIndexed { index, team ->
            val teamId = if (teamIds.isEmpty()) null else teamIds[index % teamIds.size]
            for (player in team) {
                if (player in current.arrived) lifecycle.entry(matchId, player, teamId)
            }
        }
    }

    private fun onMatchEnded(matchId: MatchId) {
        val current = session?.takeIf { it.matchId == matchId } ?: return
        scheduler.runLater(ticks(settings.returnDelaySeconds * TICKS_PER_SECOND)) {
            val participants = matches.findById(matchId)?.teams?.flatMap { it.members }.orEmpty()
            participants.forEach { returnToLobby(it) }
            finish(current)
        }
    }

    private fun finish(current: Session) {
        if (session === current) session = null
        reply(current.replyTo, AssignmentStatus.Ended(current.assignment.id))
    }

    /** Anybody on a game server without a place in the current assignment goes back to the lobby. */
    private fun bounceIfStray(playerId: PlayerId) {
        if (inActiveMatch(playerId)) return // reconnecting into their match
        notifier.notify(playerId, MessageKey("astera.match.matchmaking.wrong-server"), emptyMap())
        scheduler.runLater(ticks(BOUNCE_DELAY_SECONDS * TICKS_PER_SECOND)) {
            if (!inActiveMatch(playerId)) returnToLobby(playerId)
        }
    }

    private fun inActiveMatch(playerId: PlayerId): Boolean =
        matches.findMatchOf(playerId)?.let { it.phase != MatchPhase.ENDED } == true

    private fun returnToLobby(playerId: PlayerId) {
        val moved = transfers.transfer(playerId, settings.lobbyServer, TransferReason.LobbyReturn)
        if (moved is Result.Failure) log.warn("Return of {} to {} failed: {}", playerId, settings.lobbyServer, moved.error)
    }

    private fun reply(to: ServerName, status: AssignmentStatus) {
        val sent = messenger.sendMessage(to, ProxyChannel.MATCH_STATUS, MatchAssignmentCodec.encode(status))
        if (sent is Result.Failure) log.warn("Could not send {} to {}: {}", status, to, sent.error)
    }

    private companion object {
        const val TICKS_PER_SECOND = 20L
        const val BOUNCE_DELAY_SECONDS = 3
    }
}
