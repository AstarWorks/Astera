package com.astarworks.astera.match.application.service.matchmaking

import com.astarworks.astera.core.application.port.outbound.IProxyMessenger
import com.astarworks.astera.core.application.port.outbound.IServerTransferGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.PlayerArrivedOnServer
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ProxyChannel
import com.astarworks.astera.core.domain.proxy.ProxyError
import com.astarworks.astera.core.domain.proxy.ProxyMessage
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.core.domain.proxy.TransferError
import com.astarworks.astera.core.domain.proxy.TransferReason
import com.astarworks.astera.core.domain.proxy.TransferWhen
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.application.service.matchmaking.InMemoryMatchmaker
import com.astarworks.astera.match.application.usecase.match.EndMatchError
import com.astarworks.astera.match.application.usecase.match.EntryGameError
import com.astarworks.astera.match.application.usecase.match.StartMatchError
import com.astarworks.astera.match.application.usecase.match.SwitchPhaseError
import com.astarworks.astera.match.application.usecase.matchmaking.MatchPlan
import com.astarworks.astera.match.domain.match.MatchStateMachine
import com.astarworks.astera.match.domain.matchmaking.AssignmentStatus
import com.astarworks.astera.match.domain.matchmaking.MatchAssignment
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** The lobby -> game assignment protocol (ADR-0179) over a linked pair of fake messengers. */
class CrossServerHandoffTest {

    private val lobby = ServerName("lobby")
    private val game = ServerName("game")
    private val stage = StageId("arena")
    private val settings = CrossServerSettings(lobbyServer = lobby, gameServer = game)

    /** Two messengers wired back to back, delivering synchronously. */
    private class Link {
        val handlers = mutableMapOf<Pair<ServerName, ProxyChannel>, MutableList<(ProxyMessage) -> Unit>>()
        val sent = mutableListOf<Triple<ServerName, ServerName, ProxyChannel>>()
        var dropAll = false

        fun node(local: ServerName) = object : IProxyMessenger {
            override fun sendToServer(playerId: PlayerId, targetServer: ServerName) = Result.Success(Unit)
            override fun sendMessage(targetServer: ServerName, channel: ProxyChannel, payload: ByteArray): Result<Unit, ProxyError> {
                sent += Triple(local, targetServer, channel)
                if (!dropAll) handlers[targetServer to channel]?.toList()?.forEach { it(ProxyMessage(local, channel, payload)) }
                return Result.Success(Unit)
            }
            override fun broadcast(channel: ProxyChannel, payload: ByteArray) = Result.Success(Unit)
            override fun subscribe(channel: ProxyChannel, handler: (ProxyMessage) -> Unit): IProxyMessenger.Subscription {
                handlers.getOrPut(local to channel) { mutableListOf() }.add(handler)
                return object : IProxyMessenger.Subscription {
                    override var isActive = true
                    override fun cancel() { isActive = false; handlers[local to channel]?.remove(handler) }
                }
            }
        }
    }

    private class FakeTransfers(val onTransfer: (PlayerId, ServerName) -> Unit = { _, _ -> }) : IServerTransferGateway {
        val transfers = mutableListOf<Triple<PlayerId, ServerName, TransferReason>>()
        override fun transfer(playerId: PlayerId, targetServer: ServerName, transferReason: TransferReason): Result<Unit, TransferError> {
            transfers += Triple(playerId, targetServer, transferReason)
            onTransfer(playerId, targetServer)
            return Result.Success(Unit)
        }
        override fun queueTransfer(playerId: PlayerId, targetServer: ServerName, when_: TransferWhen, transferReason: TransferReason) =
            Result.Success(Unit)
    }

    private class FakeLiveness(var up: Boolean = true) : com.astarworks.astera.core.application.port.outbound.IServerLiveness {
        override fun isUp(server: ServerName): Boolean = up
        override fun upPeers(): Set<ServerName> = emptySet()
    }

    private class FakeLifecycle(private val matches: InMemoryMatchRepository) : IMatchLifecycle {
        val started = mutableListOf<Pair<StageId, String?>>()
        val entries = mutableListOf<Triple<MatchId, PlayerId, TeamId?>>()
        override fun start(stageId: StageId, modeKey: String?): Result<MatchId, StartMatchError> {
            started += stageId to modeKey
            val id = MatchId("m-${started.size}")
            val teams = listOf("red", "blue").map { Team(TeamId(it), MessageKey("t.$it"), MessageKey("c.$it"), emptySet()) }
            matches.save(Match(id, MatchPhase.COUNTDOWN, teams, Score(emptyMap()), stageId, 0))
            return Result.Success(id)
        }
        override fun entry(matchId: MatchId, playerId: PlayerId, teamId: TeamId?): Result<Unit, EntryGameError> {
            entries += Triple(matchId, playerId, teamId)
            matches.findById(matchId)?.let { matches.save(it.addParticipant(playerId, teamId)) }
            return Result.Success(Unit)
        }
        override fun end(matchId: MatchId, reason: EndReason): Result<Unit, EndMatchError> = Result.Success(Unit)
        override fun switchPhase(matchId: MatchId, trigger: MatchStateMachine.TransitionTrigger): Result<MatchPhase, SwitchPhaseError> =
            Result.Success(MatchPhase.ACTIVE)
    }

    /** One lobby and one game server, each with its own bus/scheduler, plus the link and transfers between them. */
    private inner class World(withGame: Boolean = true) {
        val link = Link()
        val lobbyBus = FakeDomainEventBus()
        val gameBus = FakeDomainEventBus()
        val lobbyScheduler = FakeScheduler()
        val gameScheduler = FakeScheduler()
        val notes = mutableListOf<String>()
        val notifier = com.astarworks.astera.match.application.port.outbound.matchmaking.IQueueNotifier { _, key, _ -> notes += key.value }

        val matchmaker = InMemoryMatchmaker(eventBus = lobbyBus)
        val lobbyTransfers = FakeTransfers { player, target ->
            if (target == game) gameBus.publish(PlayerArrivedOnServer(player, lobby, game))
        }
        val gameTransfers = FakeTransfers()
        val gameMatches = InMemoryMatchRepository()
        val lifecycle = FakeLifecycle(gameMatches)

        val liveness = FakeLiveness()
        val former = RemoteMatchFormer(
            matchmaker, link.node(lobby), lobbyTransfers, liveness, notifier, lobbyScheduler, settings,
        ) { MatchPlan(stage, "norma-mode") }.also { it.start() }

        val receiver = MatchAssignmentReceiver(
            link.node(game), gameTransfers, lifecycle, gameMatches, gameBus, gameScheduler, notifier, settings,
        ).also { if (withGame) it.start() }

        fun queue(n: Int): List<PlayerId> = List(n) { PlayerId(UUID.randomUUID()) }.onEach { matchmaker.enqueue(it, MatchmakingMode.Norma) }

        fun gameSeconds(n: Int) = gameScheduler.advanceTicks(n * 20L)
        fun lobbySeconds(n: Int) = lobbyScheduler.advanceTicks(n * 20L)
    }

    @Test
    fun `assignment and status frames round-trip and garbage is dropped`() {
        val a = PlayerId(UUID.randomUUID())
        val b = PlayerId(UUID.randomUUID())
        val assignment = MatchAssignment(MatchId("a-1"), stage, "annihilation", listOf(listOf(a), listOf(b)))
        assertThat(MatchAssignmentCodec.decodeAssignment(MatchAssignmentCodec.encode(assignment))).isEqualTo(assignment)
        val legacy = assignment.copy(modeKey = null)
        assertThat(MatchAssignmentCodec.decodeAssignment(MatchAssignmentCodec.encode(legacy))).isEqualTo(legacy)
        for (status in listOf(AssignmentStatus.Accepted(MatchId("a-1")), AssignmentStatus.Rejected(MatchId("a-1"), "busy"), AssignmentStatus.Ended(MatchId("a-1")))) {
            assertThat(MatchAssignmentCodec.decodeStatus(MatchAssignmentCodec.encode(status))).isEqualTo(status)
        }
        assertThat(MatchAssignmentCodec.decodeAssignment(byteArrayOf(1, 2, 3))).isNull()
        assertThat(MatchAssignmentCodec.decodeStatus(byteArrayOf())).isNull()
    }

    @Test
    fun `lobby assigns, players transfer, game plays the match and sends everyone back`() {
        val w = World()
        val players = w.queue(2)

        val formed = w.former.execute(MatchmakingMode.Norma)
        assertThat(formed).isInstanceOf(Result.Success::class.java)

        // Accepted synchronously -> both players moved to the game server.
        assertThat(w.lobbyTransfers.transfers.map { it.first }).containsExactlyInAnyOrderElementsOf(players)
        assertThat(w.lobbyTransfers.transfers.map { it.second }.toSet()).containsExactly(game)
        assertThat(w.lobbyTransfers.transfers.map { it.third }.toSet()).containsExactly(TransferReason.MatchFound)
        assertThat(w.matchmaker.pendingByMode(MatchmakingMode.Norma)).isEmpty()

        // The last transfer made everyone arrive -> the match started with the assigned stage / mode and both on teams.
        assertThat(w.lifecycle.started).containsExactly(stage to "norma-mode")
        assertThat(w.lifecycle.entries.map { it.second }).containsExactlyInAnyOrderElementsOf(players)
        assertThat(w.lifecycle.entries.map { it.third }.toSet()).hasSize(2)

        // The lobby holds the queue while the game server is busy.
        assertThat(w.former.isStageBusy(MatchmakingMode.Norma)).isTrue()

        // Match ends -> after the return delay everyone is sent back, then the lobby is released.
        val match = w.gameMatches.findActiveMatches().single()
        w.gameMatches.save(match.withPhase(MatchPhase.ENDED))
        w.gameBus.publish(MatchEnded(match.id, null, 0, EndReason.TimerExpired))
        assertThat(w.gameTransfers.transfers).isEmpty()
        w.gameSeconds(settings.returnDelaySeconds)
        assertThat(w.gameTransfers.transfers.map { it.first }).containsExactlyInAnyOrderElementsOf(players)
        assertThat(w.gameTransfers.transfers.map { it.second }.toSet()).containsExactly(lobby)
        assertThat(w.gameTransfers.transfers.map { it.third }.toSet()).containsExactly(TransferReason.LobbyReturn)
        assertThat(w.former.isStageBusy(MatchmakingMode.Norma)).isFalse()
    }

    @Test
    fun `a busy game server rejects and the lobby re-queues the players`() {
        val w = World()
        w.gameMatches.save(
            Match(MatchId("running"), MatchPhase.ACTIVE, listOf(Team(TeamId("red"), MessageKey("t"), MessageKey("c"), emptySet())), Score(emptyMap()), stage, 0),
        )
        val players = w.queue(2)

        w.former.execute(MatchmakingMode.Norma)

        assertThat(w.lobbyTransfers.transfers).isEmpty()
        assertThat(w.matchmaker.pendingByMode(MatchmakingMode.Norma).map { it.playerId }).containsExactlyInAnyOrderElementsOf(players)
        assertThat(w.notes).contains("astera.match.matchmaking.dispatch-failure")
        assertThat(w.former.isStageBusy(MatchmakingMode.Norma)).isFalse()
    }

    @Test
    fun `a game server whose heartbeat is gone holds the queue instead of assigning`() {
        val w = World()
        w.liveness.up = false
        w.queue(2)

        assertThat(w.former.isStageBusy(MatchmakingMode.Norma)).isTrue()
        assertThat(w.former.holdNotice(MatchmakingMode.Norma)).isEqualTo("server-down")
        assertThat(w.former.execute(MatchmakingMode.Norma)).isEqualTo(Result.Success(null))
        assertThat(w.link.sent).isEmpty()
        assertThat(w.matchmaker.pendingByMode(MatchmakingMode.Norma)).hasSize(2)

        w.liveness.up = true
        assertThat(w.former.holdNotice(MatchmakingMode.Norma)).isEqualTo("stage-busy")
        assertThat(w.former.execute(MatchmakingMode.Norma)).isInstanceOf(Result.Success::class.java)
        assertThat(w.link.sent).isNotEmpty()
    }

    @Test
    fun `no answer from the game server re-queues after the ack timeout`() {
        val w = World(withGame = false)
        val players = w.queue(2)

        w.former.execute(MatchmakingMode.Norma)
        assertThat(w.former.isStageBusy(MatchmakingMode.Norma)).isTrue()
        assertThat(w.matchmaker.pendingByMode(MatchmakingMode.Norma)).isEmpty()

        w.lobbySeconds(settings.ackTimeoutSeconds)

        assertThat(w.lobbyTransfers.transfers).isEmpty()
        assertThat(w.matchmaker.pendingByMode(MatchmakingMode.Norma).map { it.playerId }).containsExactlyInAnyOrderElementsOf(players)
        assertThat(w.former.isStageBusy(MatchmakingMode.Norma)).isFalse()
    }

    @Test
    fun `players who never arrive are dropped after the arrival timeout`() {
        val here = PlayerId(UUID.randomUUID())
        val missing = PlayerId(UUID.randomUUID())

        // Drive the receiver directly: accept an assignment for two, deliver one arrival.
        val link = Link()
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler()
        val matches = InMemoryMatchRepository()
        val lifecycle = FakeLifecycle(matches)
        val transfers = FakeTransfers()
        MatchAssignmentReceiver(
            link.node(game), transfers, lifecycle, matches, bus, scheduler, { _, _, _ -> }, settings,
        ).start()
        val lobbyNode = link.node(lobby)
        val statuses = mutableListOf<AssignmentStatus>()
        lobbyNode.subscribe(ProxyChannel.MATCH_STATUS) { m -> MatchAssignmentCodec.decodeStatus(m.payload)?.let(statuses::add) }
        val assignment = MatchAssignment(MatchId("a-9"), stage, null, listOf(listOf(here), listOf(missing)))
        lobbyNode.sendMessage(game, ProxyChannel.MATCH_FOUND, MatchAssignmentCodec.encode(assignment))
        assertThat(statuses).containsExactly(AssignmentStatus.Accepted(MatchId("a-9")))

        bus.publish(PlayerArrivedOnServer(here, lobby, game))
        assertThat(lifecycle.started).isEmpty() // still waiting for the second player

        scheduler.advanceTicks(settings.arrivalTimeoutSeconds * 20L)

        assertThat(lifecycle.started).hasSize(1)
        assertThat(lifecycle.entries.map { it.second }).containsExactly(here)
    }

    @Test
    fun `nobody arriving drops the assignment without a match and frees the lobby`() {
        val link = Link()
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler()
        val matches = InMemoryMatchRepository()
        val lifecycle = FakeLifecycle(matches)
        MatchAssignmentReceiver(link.node(game), FakeTransfers(), lifecycle, matches, bus, scheduler, { _, _, _ -> }, settings).start()
        val lobbyNode = link.node(lobby)
        val statuses = mutableListOf<AssignmentStatus>()
        lobbyNode.subscribe(ProxyChannel.MATCH_STATUS) { m -> MatchAssignmentCodec.decodeStatus(m.payload)?.let(statuses::add) }
        val assignment = MatchAssignment(MatchId("a-7"), stage, null, listOf(listOf(PlayerId(UUID.randomUUID()))))
        lobbyNode.sendMessage(game, ProxyChannel.MATCH_FOUND, MatchAssignmentCodec.encode(assignment))

        scheduler.advanceTicks(settings.arrivalTimeoutSeconds * 20L)

        assertThat(lifecycle.started).isEmpty()
        assertThat(statuses).containsExactly(AssignmentStatus.Accepted(MatchId("a-7")), AssignmentStatus.Ended(MatchId("a-7")))
    }

    @Test
    fun `an unassigned player who connects to the game server is sent back to the lobby`() {
        val w = World()
        val stray = PlayerId(UUID.randomUUID())

        w.gameBus.publish(PlayerArrivedOnServer(stray, lobby, game))
        assertThat(w.gameTransfers.transfers).isEmpty()
        w.gameSeconds(5)

        assertThat(w.gameTransfers.transfers).containsExactly(Triple(stray, lobby, TransferReason.LobbyReturn))
        assertThat(w.notes).contains("astera.match.matchmaking.wrong-server")
    }
    @Test
    fun `departed arrivals are not entered into a match when remaining players arrive`() {
        val w = World()
        val first = PlayerId(UUID.randomUUID())
        val last = PlayerId(UUID.randomUUID())
        val assignment = MatchAssignment(MatchId("departed"), stage, null, listOf(listOf(first), listOf(last)))
        w.link.node(lobby).sendMessage(game, ProxyChannel.MATCH_FOUND, MatchAssignmentCodec.encode(assignment))
        w.gameBus.publish(PlayerArrivedOnServer(first, lobby, game))
        w.gameBus.publish(com.astarworks.astera.core.domain.event.PlayerQuitServer(first))
        w.gameBus.publish(PlayerArrivedOnServer(last, lobby, game))
        assertThat(w.lifecycle.started).isEmpty()
        w.gameSeconds(settings.arrivalTimeoutSeconds)
        assertThat(w.lifecycle.entries.map { it.second }).containsExactly(last)
    }

    @Test
    fun `an arrival that quits can rejoin and complete the assignment`() {
        val w = World()
        val first = PlayerId(UUID.randomUUID())
        val last = PlayerId(UUID.randomUUID())
        val assignment = MatchAssignment(MatchId("rejoined"), stage, null, listOf(listOf(first), listOf(last)))
        w.link.node(lobby).sendMessage(game, ProxyChannel.MATCH_FOUND, MatchAssignmentCodec.encode(assignment))
        w.gameBus.publish(PlayerArrivedOnServer(first, lobby, game))
        w.gameBus.publish(com.astarworks.astera.core.domain.event.PlayerQuitServer(first))
        w.gameBus.publish(PlayerArrivedOnServer(first, lobby, game))
        w.gameBus.publish(PlayerArrivedOnServer(last, lobby, game))
        assertThat(w.lifecycle.entries.map { it.second }).containsExactlyInAnyOrder(first, last)
    }
}
