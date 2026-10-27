package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.event.PlayerLeavingForLobby
import com.astarworks.astera.core.domain.event.PlayerLeftMatch
import com.astarworks.astera.core.domain.event.PlayerReturnedToLobby
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.SessionState
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.core.domain.proxy.ServerRole
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 27a / 27b: a participant who leaves before the match is ACTIVE (quit or lobby command) must be taken out of the
 * roster and the match re-evaluated at once, and an abort / cancel must send every participant back to the lobby.
 * Without this a ghost stays on the roster, the match starts (or hangs) with bots only, and the player's next
 * "play vs bots" click stacks a second set of bots on the stale ones ("cancelling multiplies bots").
 */
class MatchLeaveAndAbortScenarioTest {

    private fun ScenarioScope.countdownMatch(vararg members: Pair<SimEntity, String>): MatchId {
        val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
        val id = when (val started = lifecycle.start(StageId("siege-isles"), "carry-tnt")) {
            is Result.Success -> started.value
            is Result.Failure -> error("start failed: ${started.error}")
        }
        members.forEach { (entity, team) -> lifecycle.entry(id, entity.id, TeamId(team)) }
        advance(2)
        assertThat(sim.services.getOrThrow<IMatchRepository>().findById(id)?.phase).isEqualTo(MatchPhase.COUNTDOWN)
        return id
    }

    private fun ScenarioScope.roster(id: MatchId): List<PlayerId> =
        sim.services.getOrThrow<IMatchRepository>().findById(id)?.teams?.flatMap { it.members }.orEmpty()

    @Test
    fun `a quit during the countdown takes the player off the roster and the match goes on for the rest`() {
        scenario("quit-in-countdown-2v2", standaloneTeams = false) {
            val r1 = player("red-1", team = "red")
            val r2 = player("red-2", team = "red")
            val b1 = player("blue-1", team = "blue")
            val b2 = player("blue-2", team = "blue")
            val id = countdownMatch(r1 to "red", r2 to "red", b1 to "blue", b2 to "blue")

            sim.detachPlayer(r1.id)
            advance(2)

            assertThat(roster(id)).doesNotContain(r1.id).contains(r2.id, b1.id, b2.id)
            assertThat(eventsOf<PlayerLeftMatch>().map { it.playerId }).containsExactly(r1.id)
            assertThat(sim.services.getOrThrow<IPlayerSessionRepository>().find(r1.id)?.currentMatch).isNull()
            assertThat(sim.services.getOrThrow<IMatchRepository>().findById(id)?.phase).isEqualTo(MatchPhase.COUNTDOWN)
        }
    }

    @Test
    fun `leaving for the lobby during the countdown removes the player at once`() {
        scenario("lobby-command-in-countdown", standaloneTeams = false) {
            val r1 = player("red-1", team = "red")
            val r2 = player("red-2", team = "red")
            val b1 = player("blue-1", team = "blue")
            val id = countdownMatch(r1 to "red", r2 to "red", b1 to "blue")

            sim.eventBus.publish(PlayerLeavingForLobby(r1.id))
            advance(2)

            assertThat(roster(id)).doesNotContain(r1.id)
            assertThat(eventsOf<PlayerLeftMatch>().map { it.playerId }).containsExactly(r1.id)
        }
    }

    @Test
    fun `the last player of a side quitting during the countdown cancels the match and sends the rest home`() {
        scenario("quit-in-countdown-1v1", standaloneTeams = false) {
            val red = player("red", team = "red")
            val blue = player("blue", team = "blue")
            val id = countdownMatch(red to "red", blue to "blue")

            sim.detachPlayer(red.id)
            advance(4)

            val ended = eventsOf<MatchEnded>().single { it.matchId == id }
            assertThat(ended.reason).isInstanceOf(EndReason.Cancelled::class.java)
            assertThat(sim.services.getOrThrow<IMatchRepository>().findActiveMatches()).isEmpty()
            assertThat(eventsOf<PlayerReturnedToLobby>().map { it.playerId }).contains(blue.id)
            assertThat(sim.services.getOrThrow<IMatchRepository>().findById(id)?.phase).isEqualTo(MatchPhase.ENDED)
        }
    }

    @Test
    fun `an aborted match sends every participant back to the lobby in any phase`() {
        for (activate in listOf(false, true)) {
            scenario("abort-returns-everyone-$activate", standaloneTeams = false) {
                val a = player("a", team = "red")
                val b = player("b", team = "blue")
                val c = player("c", team = "blue")
                val id = countdownMatch(a to "red", b to "blue", c to "blue")
                if (activate) {
                    sim.services.getOrThrow<IMatchLifecycle>()
                        .switchPhase(id, com.astarworks.astera.match.domain.match.MatchStateMachine.TransitionTrigger.CountdownComplete)
                    advance(40)
                    assertThat(sim.services.getOrThrow<IMatchRepository>().findById(id)?.phase).isEqualTo(MatchPhase.ACTIVE)
                }

                assertThat(sim.services.getOrThrow<IMatchLifecycle>().end(id, EndReason.Cancelled("admin-cancel")))
                    .isInstanceOf(Result.Success::class.java)
                advance(40)

                assertThat(eventsOf<PlayerReturnedToLobby>().map { it.playerId })
                    .`as`("every participant is returned to the lobby (active=$activate)")
                    .containsExactlyInAnyOrder(a.id, b.id, c.id)
                assertThat(sim.services.getOrThrow<IMatchRepository>().findActiveMatches()).isEmpty()
            }
        }
    }

    // --- lobby + game split: the bots' side of it ----------------------------------------------------------------

    private val lobbyName = ServerName("lobby")
    private val gameName = ServerName("game")

    private fun split(tag: String, body: (SimProxyNetwork, GameSim, GameSim) -> Unit) {
        val network = SimProxyNetwork()
        val lobby = GameSim.boot("$tag-lobby", role = ServerRole.LOBBY, serverName = lobbyName, proxy = network.node(lobbyName))
        val game = GameSim.boot("$tag-game", role = ServerRole.GAME, serverName = gameName, proxy = network.node(gameName))
        network.register(lobbyName, lobby)
        network.register(gameName, game)
        try {
            body(network, lobby, game)
        } finally {
            lobby.shutdown()
            game.shutdown()
        }
    }

    private fun queueUp(lobby: GameSim, vararg names: String): List<PlayerId> = names.map { name ->
        val p = lobby.spawnPlayer(name)
        check(lobby.services.getOrThrow<IMatchmaker>().enqueue(p.id, MatchmakingMode.CARRY_TNT) is Result.Success)
        p.id
    }

    @Test
    fun `the human quitting while the countdown runs does not leave a bots-only match behind`() {
        split("human-quits-countdown") { network, lobby, game ->
            val first = queueUp(lobby, "human", "Bot_Ash")
            repeat(900) { if (game.eventsOf<MatchStarted>().isEmpty()) network.step(1) }
            val matches = game.services.getOrThrow<IMatchRepository>()
            assertThat(matches.findActiveMatches().single().phase).isEqualTo(MatchPhase.COUNTDOWN)

            game.detachPlayer(first[0])
            network.step(8 * 20 + 80)

            assertThat(matches.findActiveMatches()).`as`("no match left running for the bot alone").isEmpty()
            assertThat(game.world.find(first[1])).`as`("the bot is not stranded on the game server").isNull()
            assertThat(lobby.world.find(first[1])).`as`("the bot is back on the lobby").isNotNull()

            // The game server is free: the next wave forms one clean match.
            val wave = queueUp(lobby, "n1", "n2")
            repeat(900) { if (game.eventsOf<MatchStarted>().size < 2) network.step(1) }
            network.step(5)
            val next = matches.findActiveMatches().single()
            assertThat(next.teams.flatMap { it.members }).containsExactlyInAnyOrderElementsOf(wave)
        }
    }

    @Test
    fun `a player left over on the game server after an abort is sent home too`() {
        split("abort-straggler") { network, lobby, game ->
            val first = queueUp(lobby, "human", "Bot_Ash")
            repeat(900) { if (game.eventsOf<MatchStarted>().isEmpty()) network.step(1) }
            val matches = game.services.getOrThrow<IMatchRepository>()
            val match = matches.findActiveMatches().single()
            // The human is removed from the roster (as a lobby-command leave would) but is still connected here.
            matches.save(match.copy(teams = match.teams.map { it.copy(members = it.members - first[0]) }))

            game.services.getOrThrow<IMatchLifecycle>().end(match.id, EndReason.Cancelled("admin-cancel"))
            network.step(8 * 20 + 40)

            first.forEach {
                assertThat(game.world.find(it)).`as`("$it must not stay on the game server").isNull()
                assertThat(lobby.world.find(it)).`as`("$it is back on the lobby").isNotNull()
            }
        }
    }
}
