package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
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
            lobby.services.getOrThrow<IMatchmaker>().enqueue(p1.id, MatchmakingMode.Norma)
            game.services.getOrThrow<IMatchmaker>().enqueue(p2.id, MatchmakingMode.Norma)
            lobby.step(400)
            game.step(400)
            assertThat(lobby.eventsOf<MatchStarted>()).isEmpty()
            assertThat(game.eventsOf<MatchStarted>()).isEmpty()

            // ALL still forms the match on the spot.
            val p3 = all.spawnPlayer("p3")
            all.services.getOrThrow<IMatchmaker>().enqueue(p3.id, MatchmakingMode.Norma)
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
            assertThat(lobby.services.getOrThrow<IMatchmaker>().enqueue(player.id, MatchmakingMode.Norma)).isInstanceOf(Result.Success::class.java)

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
            assertThat(network.moves.map { it.third }).containsExactly(gameName)

            // The match runs on the game server only, with the player on a team.
            assertThat(game.eventsOf<MatchStarted>()).hasSize(1)
            assertThat(lobby.eventsOf<MatchStarted>()).isEmpty()
            val matches = game.services.getOrThrow<IMatchRepository>()
            val match = matches.findActiveMatches().single()
            assertThat(match.teams.flatMap { it.members }).containsExactly(player.id)

            // While it runs, a second wave on the lobby waits for the game server.
            val late = lobby.spawnPlayer("p2")
            lobby.services.getOrThrow<IMatchmaker>().enqueue(late.id, MatchmakingMode.Norma)
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
            assertThat(game.eventsOf<MatchStarted>()).hasSize(2)
        } finally {
            lobby.shutdown()
            game.shutdown()
        }
    }
}
