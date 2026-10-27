package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IServerLiveness
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.event.PlayerArrivedOnServer
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.ProxyHeartbeat
import com.astarworks.astera.core.domain.proxy.ProxyChannel
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.core.domain.proxy.ServerRole
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0179: the lobby / game split. Two simulated servers (a `lobby`-role and a
 * `game`-role boot of the production wiring) joined by [SimProxyNetwork]:
 * queue on the lobby -> assignment over the messenger -> transfer -> match on
 * the game server -> end -> back on the lobby.
 */
class LobbyGameSplitScenarioTest {

    private val lobbyName = ServerName("lobby")
    private val gameName = ServerName("game")

    private fun commandNames(sim: GameSim): Set<String> = sim.commandTree.literals.map { it.name }.toSet()

    @Test
    fun `lobby-role server shows the lobby sidebar to a joining player`() {
        val lobby = GameSim.boot("split-lobby-sidebar", role = ServerRole.LOBBY, serverName = lobbyName)
        try {
            val player = lobby.spawnPlayer("p1")
            lobby.step(40)
            val sidebar = checkNotNull(lobby.hud.sidebar(player.id)) { "no lobby sidebar on a LOBBY-role server" }
            assertThat(sidebar.titleKey.value).isEqualTo("astera.hud.lobby.title")
            assertThat(sidebar.lines).isNotEmpty()
        } finally {
            lobby.shutdown()
        }
    }

    @Test
    fun `roles gate lobby-only and match-only features`() {
        val lobby = GameSim.boot("split-roles-lobby", role = ServerRole.LOBBY, serverName = lobbyName)
        val game = GameSim.boot("split-roles-game", role = ServerRole.GAME, serverName = gameName)
        val all = GameSim.boot("split-roles-all")
        try {
            // The queue is a lobby feature; ALL keeps today's behaviour.
            assertThat(commandNames(lobby)).contains("queue")
            assertThat(commandNames(game)).doesNotContain("queue")
            assertThat(commandNames(all)).contains("queue")

            // A queued lobby player never starts a match locally; a game server has no queue scheduler.
            val p1 = lobby.spawnPlayer("p1")
            val p2 = game.spawnPlayer("p2")
            lobby.services.getOrThrow<IMatchmaker>().enqueue(p1.id, MatchmakingMode.CARRY_TNT)
            game.services.getOrThrow<IMatchmaker>().enqueue(p2.id, MatchmakingMode.CARRY_TNT)
            lobby.step(400)
            game.step(400)
            assertThat(lobby.eventsOf<MatchStarted>()).isEmpty()
            assertThat(game.eventsOf<MatchStarted>()).isEmpty()

            // ALL still forms the match on the spot.
            val p3 = all.spawnPlayer("p3")
            val p4 = all.spawnPlayer("p4")
            all.services.getOrThrow<IMatchmaker>().enqueue(p3.id, MatchmakingMode.CARRY_TNT)
            all.services.getOrThrow<IMatchmaker>().enqueue(p4.id, MatchmakingMode.CARRY_TNT)
            assertThat(all.stepUntil(400) { all.eventsOf<MatchStarted>().isNotEmpty() }).isTrue()
        } finally {
            lobby.shutdown()
            game.shutdown()
            all.shutdown()
        }
    }

    @Test
    fun `queue on the lobby, match on the game server, back to the lobby`() {
        val network = SimProxyNetwork()
        val lobby = GameSim.boot("split-lobby", role = ServerRole.LOBBY, serverName = lobbyName, proxy = network.node(lobbyName))
        val game = GameSim.boot("split-game", role = ServerRole.GAME, serverName = gameName, proxy = network.node(gameName))
        network.register(lobbyName, lobby)
        network.register(gameName, game)
        try {
            val player = lobby.spawnPlayer("p1")
            val mate = lobby.spawnPlayer("p1b")
            assertThat(lobby.services.getOrThrow<IMatchmaker>().enqueue(player.id, MatchmakingMode.CARRY_TNT)).isInstanceOf(Result.Success::class.java)
            lobby.services.getOrThrow<IMatchmaker>().enqueue(mate.id, MatchmakingMode.CARRY_TNT)

            // Queue countdown on the lobby, then the assignment is sent and the player is transferred.
            var arrived = false
            repeat(600) {
                if (!arrived) {
                    network.step(1)
                    arrived = game.world.find(player.id) != null
                }
            }
            assertThat(arrived).`as`("player reached the game server").isTrue()
            assertThat(lobby.world.find(player.id)).`as`("player left the lobby").isNull()
            assertThat(network.sent.map { it.channel }).contains(ProxyChannel.MATCH_FOUND, ProxyChannel.MATCH_STATUS)
            assertThat(network.moves.map { it.third }).containsOnly(gameName)

            // The match starts once the hand-off token has landed and the arrival is announced.
            network.step(5)

            // The match runs on the game server only, with the player on a team.
            assertThat(game.eventsOf<MatchStarted>()).hasSize(1)
            assertThat(lobby.eventsOf<MatchStarted>()).isEmpty()
            val matches = game.services.getOrThrow<IMatchRepository>()
            val match = matches.findActiveMatches().single()
            assertThat(match.teams.flatMap { it.members }).containsExactlyInAnyOrder(player.id, mate.id)
            assertThat(match.teams.map { it.members.size }).`as`("one player per side").containsExactly(1, 1)

            // While it runs, a second wave on the lobby waits for the game server.
            val late = lobby.spawnPlayer("p2")
            val late2 = lobby.spawnPlayer("p2b")
            lobby.services.getOrThrow<IMatchmaker>().enqueue(late.id, MatchmakingMode.CARRY_TNT)
            lobby.services.getOrThrow<IMatchmaker>().enqueue(late2.id, MatchmakingMode.CARRY_TNT)
            network.step(300)
            assertThat(game.world.find(late.id)).`as`("queue is held while the game server is busy").isNull()

            // End the match: after the return delay the player is back on the lobby.
            val lifecycle = game.services.getOrThrow<IMatchLifecycle>()
            assertThat(lifecycle.end(match.id, EndReason.AdminEnded)).isInstanceOf(Result.Success::class.java)
            assertThat(game.eventsOf<MatchEnded>()).hasSize(1)
            network.step(8 * 20 + 5)

            assertThat(lobby.world.find(player.id)).`as`("player is back on the lobby").isNotNull()
            assertThat(game.world.find(player.id)).isNull()
            assertThat(network.moves.map { it.third }).contains(lobbyName)
            assertThat(lobby.eventsOf<PlayerJoinedServer>().count { it.playerId == player.id }).isEqualTo(2)

            // The game server is free again, so the waiting player's match goes through.
            var secondArrived = false
            repeat(600) {
                if (!secondArrived) {
                    network.step(1)
                    secondArrived = game.world.find(late.id) != null
                }
            }
            assertThat(secondArrived).`as`("held player is assigned once the game server is free").isTrue()
            network.step(5) // the start runs on the next Global tick after the last arrival
            assertThat(game.eventsOf<MatchStarted>()).hasSize(2)
        } finally {
            lobby.shutdown()
            game.shutdown()
        }
    }

    @Test
    fun `game drops an assignment if the only arrived player disconnects before start`() {
        val network = SimProxyNetwork()
        val lobbyNode = network.node(lobbyName)
        val game = GameSim.boot("split-arrival-quit", role = ServerRole.GAME, serverName = gameName, proxy = network.node(gameName))
        network.register(gameName, game)
        try {
            val playerId = com.astarworks.astera.core.domain.player.PlayerId(java.util.UUID.randomUUID())
            val missing = com.astarworks.astera.core.domain.player.PlayerId(java.util.UUID.randomUUID())
            val assignment = com.astarworks.astera.match.domain.matchmaking.MatchAssignment(
                com.astarworks.astera.core.domain.match.MatchId("arrival-quit"),
                com.astarworks.astera.core.domain.stage.StageId("example-arena"), null,
                listOf(listOf(playerId), listOf(missing)),
            )
            lobbyNode.sendMessage(gameName, ProxyChannel.MATCH_FOUND,
                com.astarworks.astera.match.application.service.matchmaking.MatchAssignmentCodec.encode(assignment))
            network.pump()
            game.attachPlayer(playerId, "quit-before-start")
            network.step(60)
            assertThat(game.eventsOf<PlayerArrivedOnServer>().map { it.playerId }).contains(playerId)
            game.detachPlayer(playerId)
            network.step(45 * 20)
            assertThat(game.eventsOf<MatchStarted>()).isEmpty()
            assertThat(game.services.getOrThrow<IMatchRepository>().findActiveMatches()).isEmpty()
        } finally {
            game.shutdown()
        }
    }

    /** Bug 11: a game server whose arena is still being built used to bounce every arrived player back to the lobby. */
    @Test
    fun `assigned players wait for a still-building arena instead of being sent back to the lobby`() {
        val network = SimProxyNetwork()
        val lobbyNode = network.node(lobbyName)
        val building = object : com.astarworks.astera.core.application.port.outbound.IArenaBuilder {
            @Volatile var ready = false
            override fun acquire(stage: com.astarworks.astera.core.domain.stage.Stage) =
                if (ready) Result.Success(stage)
                else Result.Failure(com.astarworks.astera.core.application.port.outbound.ArenaAllocationError.NotReady)
            override fun peekReady(stage: com.astarworks.astera.core.domain.stage.Stage): com.astarworks.astera.core.domain.stage.Stage? = if (ready) stage else null
            override fun resolve(stageId: com.astarworks.astera.core.domain.stage.StageId): com.astarworks.astera.core.domain.stage.Stage? = null
            override fun ensureBuilt(stageId: com.astarworks.astera.core.domain.stage.StageId): Boolean = ready
            override fun repairLater(stageId: com.astarworks.astera.core.domain.stage.StageId) = Unit
        }
        val game = GameSim.boot("split-arena-building", role = ServerRole.GAME, serverName = gameName, proxy = network.node(gameName), arenas = building)
        network.register(gameName, game)
        try {
            val a = com.astarworks.astera.core.domain.player.PlayerId(java.util.UUID.randomUUID())
            val b = com.astarworks.astera.core.domain.player.PlayerId(java.util.UUID.randomUUID())
            val assignment = com.astarworks.astera.match.domain.matchmaking.MatchAssignment(
                com.astarworks.astera.core.domain.match.MatchId("arena-wait"),
                com.astarworks.astera.core.domain.stage.StageId("example-arena"), null,
                listOf(listOf(a), listOf(b)),
            )
            lobbyNode.sendMessage(gameName, ProxyChannel.MATCH_FOUND,
                com.astarworks.astera.match.application.service.matchmaking.MatchAssignmentCodec.encode(assignment))
            network.pump()
            game.attachPlayer(a, "arena-wait-a")
            game.attachPlayer(b, "arena-wait-b")
            // Well past the 3 s stray-bounce delay and the arrival wait, the arena is still not built.
            network.step(10 * 20)
            assertThat(game.eventsOf<MatchStarted>()).isEmpty()
            assertThat(game.world.find(a)).`as`("player still on the game server while the arena builds").isNotNull()
            assertThat(game.world.find(b)).isNotNull()
            assertThat(network.moves).`as`("nobody was sent back to the lobby").isEmpty()

            building.ready = true
            network.step(3 * 20)
            assertThat(game.eventsOf<MatchStarted>()).hasSize(1)
            val match = game.services.getOrThrow<IMatchRepository>().findActiveMatches().single()
            assertThat(match.teams.flatMap { it.members }).containsExactlyInAnyOrder(a, b)
            assertThat(network.moves).isEmpty()
        } finally {
            game.shutdown()
        }
    }

    /** Bug 11 (root cause): the last arrival triggers the start from the player's own thread; arena claims are Global-only. */
    @Test
    fun `the match starts on the global thread so the arena claim is not refused as wrong scope`() {
        val network = SimProxyNetwork()
        val lobbyNode = network.node(lobbyName)
        var scheduler: com.astarworks.astera.testfixtures.FakeScheduler? = null
        val globalOnly = object : com.astarworks.astera.core.application.port.outbound.IArenaBuilder {
            override fun acquire(stage: com.astarworks.astera.core.domain.stage.Stage) =
                if (scheduler?.currentScope == com.astarworks.astera.core.application.port.outbound.ExecutionScope.Global) Result.Success(stage)
                else Result.Failure(com.astarworks.astera.core.application.port.outbound.ArenaAllocationError.WrongScope)
            override fun peekReady(stage: com.astarworks.astera.core.domain.stage.Stage): com.astarworks.astera.core.domain.stage.Stage? = stage
            override fun resolve(stageId: com.astarworks.astera.core.domain.stage.StageId): com.astarworks.astera.core.domain.stage.Stage? = null
            override fun ensureBuilt(stageId: com.astarworks.astera.core.domain.stage.StageId): Boolean = true
            override fun repairLater(stageId: com.astarworks.astera.core.domain.stage.StageId) = Unit
        }
        val game = GameSim.boot("split-arena-scope", role = ServerRole.GAME, serverName = gameName, proxy = network.node(gameName), arenas = globalOnly)
        scheduler = game.scheduler
        network.register(gameName, game)
        try {
            val a = com.astarworks.astera.core.domain.player.PlayerId(java.util.UUID.randomUUID())
            val b = com.astarworks.astera.core.domain.player.PlayerId(java.util.UUID.randomUUID())
            val assignment = com.astarworks.astera.match.domain.matchmaking.MatchAssignment(
                com.astarworks.astera.core.domain.match.MatchId("arena-scope"),
                com.astarworks.astera.core.domain.stage.StageId("example-arena"), null,
                listOf(listOf(a), listOf(b)),
            )
            lobbyNode.sendMessage(gameName, ProxyChannel.MATCH_FOUND,
                com.astarworks.astera.match.application.service.matchmaking.MatchAssignmentCodec.encode(assignment))
            network.pump()
            game.attachPlayer(a, "arena-scope-a")
            game.attachPlayer(b, "arena-scope-b")
            network.step(10 * 20)
            assertThat(game.eventsOf<MatchStarted>()).hasSize(1)
            assertThat(network.moves).`as`("nobody was sent back to the lobby").isEmpty()
        } finally {
            game.shutdown()
        }
    }

    private fun arrivedOn(network: SimProxyNetwork, sim: GameSim, player: com.astarworks.astera.core.domain.player.PlayerId, maxTicks: Int): Boolean {
        repeat(maxTicks) {
            if (sim.world.find(player) != null) return true
            network.step(1)
        }
        return sim.world.find(player) != null
    }

    @Test
    fun `a failing transfer notice never blocks the proxy hop in either direction`() {
        val network = SimProxyNetwork()
        val lobby = GameSim.boot("split-notice-lobby", role = ServerRole.LOBBY, serverName = lobbyName, proxy = network.node(lobbyName))
        val game = GameSim.boot("split-notice-game", role = ServerRole.GAME, serverName = gameName, proxy = network.node(gameName))
        network.register(lobbyName, lobby)
        network.register(gameName, game)
        try {
            val player = lobby.spawnPlayer("p1")
            val mate = lobby.spawnPlayer("p1b")
            checkNotNull(lobby.server.view(player.id)).failNotices = true
            lobby.services.getOrThrow<IMatchmaker>().enqueue(player.id, MatchmakingMode.CARRY_TNT)
            lobby.services.getOrThrow<IMatchmaker>().enqueue(mate.id, MatchmakingMode.CARRY_TNT)

            assertThat(arrivedOn(network, game, player.id, 800)).`as`("Connect sent despite the failing notice").isTrue()
            assertThat(network.moves.map { it.third }).containsOnly(gameName)
            network.step(5)

            checkNotNull(game.server.view(player.id)).failNotices = true
            val match = game.services.getOrThrow<IMatchRepository>().findActiveMatches().single()
            game.services.getOrThrow<IMatchLifecycle>().end(match.id, EndReason.AdminEnded)
            network.step(8 * 20 + 40)

            assertThat(lobby.world.find(player.id)).`as`("LobbyReturn hop sent despite the failing notice").isNotNull()
            assertThat(network.moves.map { it.third }).contains(lobbyName)
        } finally {
            lobby.shutdown()
            game.shutdown()
        }
    }

    @Test
    fun `inventory survives lobby to game to lobby even when the save is slow`() {
        val network = SimProxyNetwork()
        val shared = SharedSnapshotRepository(readLag = 4)
        val lobby = GameSim.boot("split-inv-lobby", role = ServerRole.LOBBY, serverName = lobbyName, proxy = network.node(lobbyName), snapshots = shared)
        val game = GameSim.boot("split-inv-game", role = ServerRole.GAME, serverName = gameName, proxy = network.node(gameName), snapshots = shared)
        network.register(lobbyName, lobby)
        network.register(gameName, game)
        try {
            val player = lobby.spawnPlayer("p1")
            val view = checkNotNull(lobby.server.view(player.id))
            network.step(60) // the direct login waits for a token that never comes, then restores the latest (nothing)
            assertThat(lobby.eventsOf<PlayerArrivedOnServer>().map { it.playerId }).containsExactly(player.id)
            checkNotNull(lobby.inventory).put(player.id, "sword")
            val mate = lobby.spawnPlayer("p1b")
            lobby.services.getOrThrow<IMatchmaker>().enqueue(player.id, MatchmakingMode.CARRY_TNT)
            lobby.services.getOrThrow<IMatchmaker>().enqueue(mate.id, MatchmakingMode.CARRY_TNT)

            assertThat(arrivedOn(network, game, player.id, 800)).`as`("player reached the game server").isTrue()
            // "Moving to ..." shown on the source before the proxy hop.
            assertThat(view.titles).contains(com.astarworks.astera.core.domain.i18n.MessageKey("astera.proxy.transfer.title"))
            assertThat(view.actionBars).contains(com.astarworks.astera.core.domain.i18n.MessageKey("astera.proxy.transfer.actionbar"))

            // The player was attached before the token was delivered: nothing restored yet, then the token lands.
            network.step(5)
            assertThat(game.eventsOf<PlayerArrivedOnServer>().map { it.playerId }).contains(player.id)
            assertThat(checkNotNull(game.inventory).items(player.id)).containsExactly("sword")
            assertThat(game.eventsOf<MatchStarted>()).hasSize(1)

            val matches = game.services.getOrThrow<IMatchRepository>()
            val lifecycle = game.services.getOrThrow<IMatchLifecycle>()
            // F3-12: entry snapshotted what arrived from the lobby; the match kit must not travel back.
            checkNotNull(game.inventory).put(player.id, "match-kit")
            lifecycle.end(matches.findActiveMatches().single().id, EndReason.AdminEnded)
            network.step(8 * 20 + 40)

            assertThat(lobby.world.find(player.id)).`as`("player is back on the lobby").isNotNull()
            assertThat(shared.staleReads).`as`("the lagging store was actually read stale").isGreaterThan(0)
            assertThat(checkNotNull(lobby.inventory).items(player.id)).containsExactly("sword")
            assertThat(checkNotNull(game.inventory).items(player.id)).isEmpty()
            assertThat(lobby.eventsOf<PlayerArrivedOnServer>().last().fromServer).isEqualTo(gameName)
        } finally {
            lobby.shutdown()
            game.shutdown()
        }
    }

    @Test
    fun `a failed restore on the game server never overwrites the saved lobby inventory`() {
        val network = SimProxyNetwork()
        val shared = SharedSnapshotRepository()
        var readsFail = true
        // The game server's view of the shared store: reads fail (DB outage) until the flag is cleared; writes land.
        val gameView = object : com.astarworks.astera.core.application.port.outbound.IPlayerInventorySnapshotRepository by shared {
            override fun findLatest(playerId: com.astarworks.astera.core.domain.player.PlayerId) =
                if (readsFail) throw IllegalStateException("sim: snapshot store down") else shared.findLatest(playerId)
        }
        val lobby = GameSim.boot("split-fail-lobby", role = ServerRole.LOBBY, serverName = lobbyName, proxy = network.node(lobbyName), snapshots = shared)
        val game = GameSim.boot("split-fail-game", role = ServerRole.GAME, serverName = gameName, proxy = network.node(gameName), snapshots = gameView)
        network.register(lobbyName, lobby)
        network.register(gameName, game)
        try {
            val player = lobby.spawnPlayer("p1")
            network.step(60)
            checkNotNull(lobby.inventory).put(player.id, "sword")
            val mate = lobby.spawnPlayer("p1b")
            lobby.services.getOrThrow<IMatchmaker>().enqueue(player.id, MatchmakingMode.CARRY_TNT)
            lobby.services.getOrThrow<IMatchmaker>().enqueue(mate.id, MatchmakingMode.CARRY_TNT)

            assertThat(arrivedOn(network, game, player.id, 800)).isTrue()
            network.step(80) // restore wait expires with the store down
            assertThat(game.eventsOf<MatchStarted>()).hasSize(1)
            assertThat(checkNotNull(game.inventory).items(player.id)).`as`("restore failed: nothing was applied").isEmpty()
            readsFail = false
            assertThat(shared.findLatest(player.id)).`as`("saved lobby snapshot untouched by the game-side entry capture").isNotNull()

            val match = game.services.getOrThrow<IMatchRepository>().findActiveMatches().single()
            game.services.getOrThrow<IMatchLifecycle>().end(match.id, EndReason.AdminEnded)
            network.step(8 * 20 + 40)

            assertThat(lobby.world.find(player.id)).isNotNull()
            assertThat(checkNotNull(lobby.inventory).items(player.id)).containsExactly("sword")
        } finally {
            lobby.shutdown()
            game.shutdown()
        }
    }

    @Test
    fun `game server down holds the queue and the assignment goes out once it is back`() {
        val network = SimProxyNetwork()
        val lobby = GameSim.boot("split-down-lobby", role = ServerRole.LOBBY, serverName = lobbyName, proxy = network.node(lobbyName))
        val game = GameSim.boot("split-down-game", role = ServerRole.GAME, serverName = gameName, proxy = network.node(gameName))
        network.register(lobbyName, lobby)
        network.register(gameName, game)
        try {
            val liveness = lobby.services.getOrThrow<IServerLiveness>()
            network.step(10)
            assertThat(liveness.isUp(gameName)).isTrue()

            network.setDown(gameName, true)
            network.step(5 * 20 + 310) // > 15 s without a heartbeat
            assertThat(liveness.isUp(gameName)).isFalse()
            assertThat(lobby.eventsOf<ProxyHeartbeat>().last().knownPeers).isEmpty()

            val player = lobby.spawnPlayer("p1")
            val mate = lobby.spawnPlayer("p1b")
            lobby.services.getOrThrow<IMatchmaker>().enqueue(player.id, MatchmakingMode.CARRY_TNT)
            lobby.services.getOrThrow<IMatchmaker>().enqueue(mate.id, MatchmakingMode.CARRY_TNT)
            network.step(800)

            assertThat(network.sent.map { it.channel }).doesNotContain(ProxyChannel.MATCH_FOUND)
            assertThat(lobby.world.find(player.id)).`as`("queued player stays on the lobby").isNotNull()
            assertThat(lobby.services.getOrThrow<IMatchmaker>().queueStatus(player.id)).isNotNull()

            network.setDown(gameName, false)
            assertThat(arrivedOn(network, game, player.id, 800)).`as`("assigned once the game server is back").isTrue()
            assertThat(liveness.isUp(gameName)).isTrue()
        } finally {
            lobby.shutdown()
            game.shutdown()
        }
    }
}
