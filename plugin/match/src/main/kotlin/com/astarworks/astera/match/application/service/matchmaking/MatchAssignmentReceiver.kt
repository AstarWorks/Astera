package com.astarworks.astera.match.application.service.matchmaking

import com.astarworks.astera.core.application.port.outbound.ArenaAllocationError
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IArenaBuilder
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.HudRegion
import com.astarworks.astera.core.domain.stage.SpawnPoint
import com.astarworks.astera.core.domain.stage.SpawnRole
import com.astarworks.astera.match.application.usecase.match.StartMatchError
import com.astarworks.astera.match.application.usecase.match.StartMatchUseCase
import com.astarworks.astera.core.application.port.outbound.IProxyMessenger
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IServerTransferGateway
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.PlayerArrivedOnServer
import com.astarworks.astera.core.domain.event.PlayerPortalReady
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ProxyChannel
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.core.domain.proxy.TransferReason
import com.astarworks.astera.match.application.admin.MatchMinPlayerWaivers
import com.astarworks.astera.match.application.usecase.match.StartMatchError
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
 * 2. Assigned players arrive through the proxy ([PlayerArrivedOnServer], after the hand-off restore). When all
 *    of them are here, or [CrossServerSettings.arrivalTimeoutSeconds] ran out,
 *    the match starts and every arrived player enters on their team. Players
 *    who never showed up are dropped. Nobody arrived: no match, `Ended`.
 * 3. When the match ends, after [CrossServerSettings.returnDelaySeconds] every
 *    participant is transferred back to the lobby server and `Ended` is sent.
 *
 * Players are only ever put where blocks stand: the arrival spawn is read from the READY, built arena slot
 * ([IArenaBuilder.peekReady]), never from the stage template (its coordinates hold no blocks: the void world
 * swallows the player). While no arena is ready nobody is placed; the arrived players wait for it (up to
 * [CrossServerSettings.arenaWaitSeconds]) rather than being bounced. Entrants stay damage-protected until their entry teleport landed.
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
    private val waivers: MatchMinPlayerWaivers? = null,
    private val arenas: IArenaBuilder,
    private val stages: IStageRegistry? = null,
    private val worlds: IWorldGateway? = null,
    private val hud: IHudGateway? = null,
) : IArrivalSpawns {

    private val log = LoggerFactory.getLogger(javaClass)

    private class Session(
        val assignment: MatchAssignment,
        val replyTo: ServerName,
        val arrived: MutableSet<PlayerId> = java.util.concurrent.ConcurrentHashMap.newKeySet(),
        var matchId: MatchId? = null,
        var arrivalTimeout: IScheduler.TaskHandle? = null,
        /** Everyone needed is here (or the timeout ran out) and the match is waiting for its arena to finish building. */
        var starting: Boolean = false,
        var arenaDeadlineTick: Long = 0,
        var arenaWaitLogged: Boolean = false,
    )

    @Volatile
    private var session: Session? = null
    private val handles = mutableListOf<() -> Unit>()

    /** Entrants whose entry teleport has not landed yet (plus a short landing grace): no damage meanwhile. */
    private val placing: MutableSet<PlayerId> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Subscribes to the assignment channel and the events it reacts to. Call [stop] on shutdown. */
    public fun start() {
        val assignments = messenger.subscribe(ProxyChannel.MATCH_FOUND) { message ->
            val assignment = MatchAssignmentCodec.decodeAssignment(message.payload)
            if (assignment == null) log.warn("Dropping malformed match assignment from {}", message.fromServer) else onAssignment(assignment, message.fromServer)
        }
        val joined = eventBus.subscribe(PlayerArrivedOnServer::class) { onPlayerJoined(it.playerId) }
        val quit = eventBus.subscribe(PlayerQuitServer::class) { event ->
            // Before the match starts a departed player is simply forgotten; afterwards the abandon watchdog applies.
            session?.takeIf { it.matchId == null }?.arrived?.remove(event.playerId)
            onPlayerQuit(event.playerId)
        }
        val ended = eventBus.subscribe(MatchEnded::class) { onMatchEnded(it.matchId) }
        val placed = eventBus.subscribe(PlayerPortalReady.EntryReady::class) { event ->
            if (event.playerId in placing) scheduler.run(ExecutionScope.Global, ticks(LANDING_GRACE_TICKS)) { placing -= event.playerId }
        }
        handles += { placed.cancel() }
        handles += { assignments.cancel() }
        handles += { joined.cancel() }
        handles += { quit.cancel() }
        handles += { ended.cancel() }
    }

    public fun stop() {
        handles.forEach { runCatching(it) }
        handles.clear()
        session?.arrivalTimeout?.cancel()
        session = null
        placing.clear()
    }

    private fun onAssignment(assignment: MatchAssignment, from: ServerName) {
        if (session != null || matches.findActiveMatches().isNotEmpty()) {
            reply(from, AssignmentStatus.Rejected(assignment.id, "busy"))
            return
        }
        val created = Session(assignment, from)
        created.arrivalTimeout = scheduler.run(ExecutionScope.Global, ticks(settings.arrivalTimeoutSeconds * TICKS_PER_SECOND)) { onArrivalTimeout(created) }
        session = created
        reply(from, AssignmentStatus.Accepted(assignment.id))
        log.info("Accepted assignment {} ({} players, stage {})", assignment.id, assignment.players.size, assignment.stageId)
    }

    private fun onPlayerJoined(playerId: PlayerId) {
        val current = session
        if (current != null && current.matchId != null && playerId in current.assignment.players && inActiveMatch(playerId)) {
            // A participant coming back to the running match: counted as present again (the abandon watchdog needs it).
            current.arrived += playerId
            return
        }
        if (current == null || current.matchId != null || playerId !in current.assignment.players) {
            bounceIfStray(playerId)
            return
        }
        current.arrived += playerId
        placeOnArena(current, playerId)
        // While the arena is still being built the start loop owns the match; late arrivals just join its roster.
        if (current.starting) return
        if (current.arrived.size == current.assignment.players.size) startOnGlobal(current) else showWaiting(current)
    }

    /**
     * Arrival events fire on the arriving player's own region thread, but claiming an arena (`IArenaBuilder.acquire`)
     * is Global-only and answers `WrongScope` anywhere else, which used to bounce every assigned player to the lobby.
     */
    private fun startOnGlobal(current: Session) {
        scheduler.run(ExecutionScope.Global, kotlin.time.Duration.ZERO) { startMatch(current) }
    }

    override fun spawnFor(playerId: PlayerId): SpawnPoint? {
        val current = session?.takeIf { it.matchId == null } ?: return null
        val teamIndex = current.assignment.teams.indexOfFirst { playerId in it }
        if (teamIndex < 0) return null
        val template = stages?.find(current.assignment.stageId) ?: return null
        // Not ready yet: no spawn at all (a template spawn would be a void coordinate).
        val stage = arenas.peekReady(template) ?: return null
        val teamIds = teamIdsFor(current.assignment.modeKey)
        val teamId = teamIds[teamIndex % teamIds.size]
        val own = stage.spawnPoints.filter { it.role == SpawnRole.TEAM && it.team == teamId }
        if (own.isNotEmpty()) {
            val slot = current.assignment.teams[teamIndex].indexOf(playerId)
            return own[slot.coerceAtLeast(0) % own.size]
        }
        return stage.spawnPoints.firstOrNull { it.role == SpawnRole.LOBBY }
    }

    override fun isWaiting(playerId: PlayerId): Boolean {
        val current = session ?: return false
        return (current.matchId == null && playerId in current.assignment.players) || playerId in placing
    }

    private fun teamIdsFor(modeKey: String?) =
        if (modeKey == StartMatchUseCase.MODE_KEY_ANNIHILATION) StartMatchUseCase.DEFAULT_ANNIHILATION_TEAMS else StartMatchUseCase.DEFAULT_TEAMS

    /** Fallback for a login that was not placed at spawn time: teleport onto the arena now. */
    private fun placeOnArena(current: Session, playerId: PlayerId) {
        val gateway = worlds ?: return
        val spawn = spawnFor(playerId) ?: return
        gateway.teleportAsync(playerId, spawn.pos, spawn.world, spawn.yaw, spawn.pitch).thenAccept { moved -> if (!moved) {
            log.warn("Arrival teleport of {} to {} failed (assignment {}).", playerId, spawn.world, current.assignment.id)
        } }
    }

    private fun showWaiting(current: Session) {
        val gateway = hud ?: return
        val remaining = current.assignment.players.size - current.arrived.size
        current.arrived.forEach { player ->
            gateway.showFor(
                player,
                HudLayout.ActionBar(
                    component = HudComponent.Text(
                        MessageKey("astera.match.matchmaking.arrival-waiting"),
                        placeholders = mapOf("remaining" to remaining.toString()),
                    ),
                    ttlTicks = 1200,
                    priority = 1,
                ),
            )
        }
    }

    private fun showArenaPreparing(current: Session) {
        val gateway = hud ?: return
        current.arrived.forEach { player ->
            gateway.showFor(
                player,
                HudLayout.ActionBar(
                    component = HudComponent.Text(MessageKey("astera.match.matchmaking.arena-preparing")),
                    ttlTicks = 60,
                    priority = 1,
                ),
            )
        }
    }

    private fun clearWaiting(current: Session) {
        val gateway = hud ?: return
        current.arrived.forEach { gateway.clearFor(it, HudRegion.ACTION_BAR) }
    }

    /**
     * Everybody left a running match (all quit or were bounced): without this the game server stays "busy"
     * until the match's own time limit. After [ABANDON_GRACE_SECONDS] with nobody on the server the match is
     * cancelled, which frees the server through the normal `Ended` path.
     */
    private fun onPlayerQuit(playerId: PlayerId) {
        val current = session ?: return
        val matchId = current.matchId ?: return
        if (playerId !in current.arrived) return
        current.arrived -= playerId
        if (current.arrived.isNotEmpty()) return
        scheduler.run(ExecutionScope.Global, ticks(ABANDON_GRACE_SECONDS * TICKS_PER_SECOND)) {
            if (session !== current || current.arrived.isNotEmpty()) return@run
            val live = matches.findById(matchId)?.takeIf { it.phase != MatchPhase.ENDED } ?: return@run
            log.warn("Match {} abandoned by all players; cancelling it.", live.id)
            lifecycle.end(live.id, EndReason.Cancelled("abandoned"))
        }
    }

    private fun onArrivalTimeout(current: Session) {
        if (session !== current || current.matchId != null || current.starting) return
        val missing = current.assignment.players.size - current.arrived.size
        log.warn("Assignment {}: {} player(s) never arrived; starting without them.", current.assignment.id, missing)
        startMatch(current)
    }

    private fun startMatch(current: Session) {
        if (session !== current || current.matchId != null) return
        current.arrivalTimeout?.cancel()
        clearWaiting(current)
        if (!current.starting) {
            current.starting = true
            current.arenaDeadlineTick = scheduler.currentTick() + settings.arenaWaitSeconds * TICKS_PER_SECOND
        }
        if (current.arrived.isEmpty()) {
            log.warn("Assignment {}: nobody arrived; dropping it.", current.assignment.id)
            finish(current)
            return
        }
        val assignment = current.assignment
        val matchId = when (val started = lifecycle.start(assignment.stageId, assignment.modeKey)) {
            is Result.Success -> started.value
            is Result.Failure -> {
                val err = started.error
                // A freshly (re)started game server pastes its arenas for a while, and the next arena is rebuilt after
                // every match. Players are already here: wait for the arena instead of bouncing them back to the lobby.
                if (err is StartMatchError.ArenaUnavailable && err.reason == ArenaAllocationError.NotReady &&
                    scheduler.currentTick() < current.arenaDeadlineTick
                ) {
                    if (!current.arenaWaitLogged) {
                        current.arenaWaitLogged = true
                        log.info("Assignment {}: arena {} is still being built; waiting up to {} s.", assignment.id, assignment.stageId, settings.arenaWaitSeconds)
                    }
                    showArenaPreparing(current)
                    scheduler.run(ExecutionScope.Global, ticks(ARENA_RETRY_TICKS)) { if (session === current) startMatch(current) }
                    return
                }
                log.warn("Assignment {}: match could not start: {}", assignment.id, err)
                current.arrived.forEach { returnToLobby(it) }
                finish(current)
                return
            }
        }
        current.matchId = matchId
        if (assignment.forced) waivers?.waive(matchId)
        current.arrived.forEach { placing += it }
        scheduler.run(ExecutionScope.Global, ticks(PLACEMENT_TIMEOUT_TICKS)) { current.arrived.forEach { placing -= it } }
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
        scheduler.run(ExecutionScope.Global, ticks(settings.returnDelaySeconds * TICKS_PER_SECOND)) {
            // The roster plus everyone still connected here: a player who left the roster (or never made it onto
            // it) but is standing on this server must not be stranded when the match is aborted or cancelled.
            val participants = matches.findById(matchId)?.teams?.flatMap { it.members }.orEmpty() + current.arrived
            participants.toSet().forEach { returnToLobby(it) }
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
        scheduler.run(ExecutionScope.Global, ticks(BOUNCE_DELAY_SECONDS * TICKS_PER_SECOND)) {
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
        const val ARENA_RETRY_TICKS = 20L
        const val ABANDON_GRACE_SECONDS = 30L
        const val LANDING_GRACE_TICKS = 100L
        const val PLACEMENT_TIMEOUT_TICKS = 300L
    }
}
