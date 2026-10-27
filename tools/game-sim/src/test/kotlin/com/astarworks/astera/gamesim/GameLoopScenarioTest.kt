package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.event.PlayerRespawned
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.core.domain.proxy.ServerRole
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Founder playtest regressions of the core loop (queue floor for PvP lanes, deaths on the game server).
 *
 * Single-sim scenarios cover the queue floor; the two-sim ones (lobby + game joined by [SimProxyNetwork]) cover what
 * only exists across servers: the admin force-start override, a death inside the hosted match and the result window.
 */
class GameLoopScenarioTest {

    private val lobbyName = ServerName("lobby")
    private val gameName = ServerName("game")

    private fun ScenarioScope.router(): IGuiIntentRouter = sim.services.getOrThrow()

    // ---- queue floor ---------------------------------------------------------------------------------------------

    @Test
    fun `a lone player in a pvp lane waits for opponents and never starts a match`() {
        scenario("game-loop-solo-queue") {
            val solo = player("solo")
            val matchmaker = sim.services.getOrThrow<IMatchmaker>()
            matchmaker.enqueue(solo.id, MatchmakingMode.CARRY_TNT)

            // Well past the queue countdown (10 s) and several scheduler passes.
            advance(20 * 30)
            assertThat(eventsOf<MatchStarted>()).`as`("a PvP match started with one player").isEmpty()
            assertThat(matchmaker.queueStatus(solo.id)).`as`("still queued").isNotNull()
            val messages = checkNotNull(sim.server.view(solo.id)).messages
            assertThat(messages).anyMatch { "Waiting for opponents" in it }
            // Told once per queue size, not every second.
            assertThat(messages.count { "Waiting for opponents" in it }).isEqualTo(1)

            // A second player on the other side starts it.
            val rival = player("rival")
            matchmaker.enqueue(rival.id, MatchmakingMode.CARRY_TNT)
            assertThat(advanceUntil(20 * 40) { eventsOf<MatchStarted>().isNotEmpty() }).isTrue()
            val match = sim.services.getOrThrow<IMatchRepository>().findActiveMatches().single()
            assertThat(match.teams.map { it.members.size }).`as`("one player per side").containsExactly(1, 1)
        }
    }

    @Test
    fun `annihilation needs a player for each of its three sides`() {
        scenario("game-loop-annihilation-floor") {
            val matchmaker = sim.services.getOrThrow<IMatchmaker>()
            val mode = MatchmakingMode.Custom(com.astarworks.astera.match.domain.mode.GameModeId("annihilation"))
            val a = player("a")
            val b = player("b")
            matchmaker.enqueue(a.id, mode)
            matchmaker.enqueue(b.id, mode)
            advance(20 * 30)
            assertThat(eventsOf<MatchStarted>()).`as`("two players cannot fill three teams").isEmpty()

            val c = player("c")
            matchmaker.enqueue(c.id, mode)
            assertThat(advanceUntil(20 * 40) { eventsOf<MatchStarted>().isNotEmpty() }).isTrue()
            val match = sim.services.getOrThrow<IMatchRepository>().findActiveMatches().single()
            assertThat(match.teams.map { it.members.size }).containsExactly(1, 1, 1)
        }
    }

    // ---- two servers ----------------------------------------------------------------------------------------------

    private fun arrived(network: SimProxyNetwork, sim: GameSim, player: PlayerId, maxTicks: Int): Boolean {
        repeat(maxTicks) {
            if (sim.world.find(player) != null) return true
            network.step(1)
        }
        return sim.world.find(player) != null
    }

    @Test
    fun `admin force start still lets one player into a pvp match on the game server`() {
        val network = SimProxyNetwork()
        val lobby = GameSim.boot("loop-force-lobby", role = ServerRole.LOBBY, serverName = lobbyName, proxy = network.node(lobbyName))
        val game = GameSim.boot("loop-force-game", role = ServerRole.GAME, serverName = gameName, proxy = network.node(gameName))
        network.register(lobbyName, lobby)
        network.register(gameName, game)
        try {
            val admin = lobby.spawnPlayer("admin")
            check(lobby.services.getOrThrow<IPermissionService>().assign(admin.id, RoleId("admin")) is Result.Success)
            lobby.services.getOrThrow<IMatchmaker>().enqueue(admin.id, MatchmakingMode.CARRY_TNT)
            network.step(40)
            assertThat(game.eventsOf<MatchStarted>()).`as`("queue alone does not start the PvP lane").isEmpty()

            val view = checkNotNull(lobby.server.view(admin.id))
            lobby.services.getOrThrow<IGuiIntentRouter>().dispatch(view, GuiIntent("admin.match.lane-force", mapOf("lane" to "carry-tnt")))

            assertThat(arrived(network, game, admin.id, 400)).`as`("forced assignment reaches the game server").isTrue()
            network.step(5)
            assertThat(game.eventsOf<MatchStarted>()).hasSize(1)
            val matchId = game.services.getOrThrow<IMatchRepository>().findActiveMatches().single().id
            // The game server waives its own floor for a forced assignment: the countdown ends in ACTIVE.
            var live = false
            repeat(20 * 30) {
                if (!live) {
                    network.step(1)
                    live = game.services.getOrThrow<IMatchRepository>().findById(matchId)?.phase == MatchPhase.ACTIVE
                }
            }
            assertThat(live).`as`("forced solo match goes ACTIVE instead of hanging in COUNTDOWN").isTrue()
        } finally {
            lobby.shutdown()
            game.shutdown()
        }
    }

    @Test
    fun `a death inside the hosted match respawns on the game server and never moves the player to the lobby`() {
        val network = SimProxyNetwork()
        val lobby = GameSim.boot("loop-death-lobby", role = ServerRole.LOBBY, serverName = lobbyName, proxy = network.node(lobbyName))
        val game = GameSim.boot("loop-death-game", role = ServerRole.GAME, serverName = gameName, proxy = network.node(gameName))
        network.register(lobbyName, lobby)
        network.register(gameName, game)
        try {
            val red = lobby.spawnPlayer("red")
            val blue = lobby.spawnPlayer("blue")
            val matchmaker = lobby.services.getOrThrow<IMatchmaker>()
            matchmaker.enqueue(red.id, MatchmakingMode.CARRY_TNT)
            matchmaker.enqueue(blue.id, MatchmakingMode.CARRY_TNT)
            assertThat(arrived(network, game, red.id, 800)).isTrue()
            assertThat(arrived(network, game, blue.id, 200)).isTrue()
            network.step(5)

            val matches = game.services.getOrThrow<IMatchRepository>()
            val matchId = matches.findActiveMatches().single().id
            var active = false
            repeat(20 * 30) {
                if (!active) {
                    network.step(1)
                    active = matches.findById(matchId)?.phase == MatchPhase.ACTIVE
                }
            }
            assertThat(active).isTrue()

            val bus = game.services.getOrThrow<IDomainEventBus>()
            bus.publish(PlayerKilled(blue.id, red.id, DamageSource.Environment.Fall(1.0), game.tick))
            network.step(20 * 8)

            assertThat(game.eventsOf<PlayerRespawned>().map { it.playerId }).`as`("death -> respawn in the match").contains(blue.id)
            assertThat(game.world.find(blue.id)).`as`("still on the game server").isNotNull()
            assertThat(lobby.world.find(blue.id)).`as`("not sent to the lobby").isNull()
            assertThat(game.spectator.isSpectating(blue.id)).isFalse()
            assertThat(matches.findById(matchId)?.phase).`as`("the match goes on").isEqualTo(MatchPhase.ACTIVE)
            assertThat(game.eventsOf<MatchEnded>()).isEmpty()
        } finally {
            lobby.shutdown()
            game.shutdown()
        }
    }

    @Test
    fun `a death in the result window after the match ended revives in place and the transfer still brings everyone home`() {
        val network = SimProxyNetwork()
        val lobby = GameSim.boot("loop-window-lobby", role = ServerRole.LOBBY, serverName = lobbyName, proxy = network.node(lobbyName))
        val game = GameSim.boot("loop-window-game", role = ServerRole.GAME, serverName = gameName, proxy = network.node(gameName))
        network.register(lobbyName, lobby)
        network.register(gameName, game)
        try {
            val red = lobby.spawnPlayer("red")
            val blue = lobby.spawnPlayer("blue")
            val matchmaker = lobby.services.getOrThrow<IMatchmaker>()
            matchmaker.enqueue(red.id, MatchmakingMode.CARRY_TNT)
            matchmaker.enqueue(blue.id, MatchmakingMode.CARRY_TNT)
            assertThat(arrived(network, game, red.id, 800)).isTrue()
            assertThat(arrived(network, game, blue.id, 200)).isTrue()
            network.step(5)

            val matches = game.services.getOrThrow<IMatchRepository>()
            val matchId = matches.findActiveMatches().single().id
            game.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.AdminEnded)
            val spawnBefore = checkNotNull(game.world.find(blue.id)).position

            // Killed while the result screen is up: no lobby teleport on a game-only backend, just a revive.
            game.services.getOrThrow<IDomainEventBus>()
                .publish(PlayerKilled(blue.id, red.id, DamageSource.Environment.Fall(1.0), game.tick))
            network.step(2)
            assertThat(checkNotNull(game.world.find(blue.id)).position).`as`("not teleported to the game server's lobby spawn").isEqualTo(spawnBefore)
            assertThat(checkNotNull(game.hp(checkNotNull(game.world.find(blue.id)))))
                .`as`("revived, not left at 0 HP").isGreaterThan(0.0)

            network.step(8 * 20 + 40)
            assertThat(lobby.world.find(red.id)).isNotNull()
            assertThat(lobby.world.find(blue.id)).`as`("the proxy transfer brought the victim home too").isNotNull()
        } finally {
            lobby.shutdown()
            game.shutdown()
        }
    }
}
