package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.bots.BotRelease
import com.astarworks.astera.core.application.bots.BotRequest
import com.astarworks.astera.core.application.bots.BotStatus
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.bots.BotRequestError
import com.astarworks.astera.core.application.port.outbound.bots.IBotRequestGateway
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.core.domain.proxy.ServerRole
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * "Cancel a match mid-way and the bots multiply": bots are ordinary players (protocol clients), so every one of them
 * must be back on the lobby after a cancel (none stranded on the game server), out of the queue, and the next
 * wave must form exactly one match with exactly the players that queued.
 */
class MatchCancelBotLeakScenarioTest {

    private val lobbyName = ServerName("lobby")
    private val gameName = ServerName("game")

    private class Rig(val network: SimProxyNetwork, val lobby: GameSim, val game: GameSim)

    private fun rig(tag: String, body: Rig.() -> Unit) {
        val network = SimProxyNetwork()
        val lobby = GameSim.boot("$tag-lobby", role = ServerRole.LOBBY, serverName = lobbyName, proxy = network.node(lobbyName))
        val game = GameSim.boot("$tag-game", role = ServerRole.GAME, serverName = gameName, proxy = network.node(gameName))
        network.register(lobbyName, lobby)
        network.register(gameName, game)
        try {
            Rig(network, lobby, game).body()
        } finally {
            lobby.shutdown()
            game.shutdown()
        }
    }

    private fun Rig.queueUp(vararg names: String): List<PlayerId> = names.map { name ->
        val p = lobby.spawnPlayer(name)
        check(lobby.services.getOrThrow<IMatchmaker>().enqueue(p.id, MatchmakingMode.SKIRMISH) is Result.Success)
        p.id
    }

    private fun Rig.runUntilMatch(startedCount: Int) {
        repeat(900) { if (game.eventsOf<MatchStarted>().size < startedCount) network.step(1) }
        assertThat(game.eventsOf<MatchStarted>()).hasSize(startedCount)
    }

    private fun Rig.cancelAndSettle(phase: MatchPhase) {
        val matches = game.services.getOrThrow<IMatchRepository>()
        val match = matches.findActiveMatches().single()
        assertThat(match.phase).isEqualTo(phase)
        assertThat(game.services.getOrThrow<IMatchLifecycle>().end(match.id, EndReason.Cancelled("admin-cancel")))
            .isInstanceOf(Result.Success::class.java)
        network.step(8 * 20 + 40)
    }

    private fun Rig.assertEveryoneHome(ids: List<PlayerId>) {
        ids.forEach {
            assertThat(game.world.find(it)).`as`("$it must not stay on the game server").isNull()
            assertThat(lobby.world.find(it)).`as`("$it is back on the lobby").isNotNull()
            assertThat(lobby.services.getOrThrow<IMatchmaker>().queueStatus(it)).`as`("$it is not queued").isNull()
        }
        assertThat(game.services.getOrThrow<IMatchRepository>().findActiveMatches()).isEmpty()
    }

    private fun Rig.nextWaveFormsOneMatch(first: List<PlayerId>) {
        val wave = queueUp("n1", "n2")
        runUntilMatch(2)
        network.step(5)
        val match = game.services.getOrThrow<IMatchRepository>().findActiveMatches().single()
        assertThat(match.teams.flatMap { it.members }).containsExactlyInAnyOrderElementsOf(wave)
        assertThat(match.teams.flatMap { it.members }).doesNotContainAnyElementsOf(first)
    }

    @Test
    fun `cancel while the match is in countdown leaves no bot behind and the next match is clean`() {
        rig("cancel-countdown") {
            val first = queueUp("human", "Bot_Ash")
            runUntilMatch(1)
            cancelAndSettle(MatchPhase.COUNTDOWN)
            assertEveryoneHome(first)
            nextWaveFormsOneMatch(first)
        }
    }

    @Test
    fun `cancel while the match is active leaves no bot behind and the next match is clean`() {
        rig("cancel-active") {
            val first = queueUp("human", "Bot_Ash")
            runUntilMatch(1)
            val matchId = game.services.getOrThrow<IMatchRepository>().findActiveMatches().single().id
            repeat(400) { if (game.services.getOrThrow<IMatchRepository>().findById(matchId)?.phase != MatchPhase.ACTIVE) network.step(1) }
            cancelAndSettle(MatchPhase.ACTIVE)
            assertEveryoneHome(first)
            nextWaveFormsOneMatch(first)
        }
    }
}

/**
 * The lobby half of the same bug. Bots are asked for one player over Redis; when that player cancels (leaves the queue
 * before the match formed, disconnects, re-clicks "vs bots") the lobby must tell the runner to drop those bots,
 * otherwise they stay queued and the next click stacks more of them (and they can form a bots-only match).
 */
class BotReleaseOnCancelScenarioTest {

    /** The runner, as far as the lobby can see it: records the wire traffic and logs bots in / out on command. */
    private class FakeRunner : IBotRequestGateway {
        val requests = mutableListOf<BotRequest>()
        val releases = mutableListOf<BotRelease>()
        private var onRequest: (BotRequest) -> Unit = {}
        private var onRelease: (BotRelease) -> Unit = {}

        fun behave(request: (BotRequest) -> Unit, release: (BotRelease) -> Unit) {
            onRequest = request
            onRelease = release
        }

        override fun publish(request: BotRequest): Result<Unit, BotRequestError> {
            requests += request
            onRequest(request)
            return Result.Success(Unit)
        }

        override fun release(release: BotRelease): Result<Unit, BotRequestError> {
            releases += release
            onRelease(release)
            return Result.Success(Unit)
        }

        override fun subscribe(handler: (BotStatus) -> Unit) = object : IBotRequestGateway.Subscription {
            override fun cancel() = Unit
        }
    }

    private fun GameSim.click(player: SimEntity, intent: String) {
        val view = checkNotNull(server.view(player.id))
        services.getOrThrow<IGuiIntentRouter>().dispatch(view, GuiIntent(intent, mapOf("lane" to "skirmish")))
    }

    @Test
    fun `leaving the queue releases the bots that came for the player and a re-click does not stack`() {
        val runner = FakeRunner()
        val lobby = GameSim.boot("bot-release", role = ServerRole.LOBBY, serverName = ServerName("lobby"), botGateway = runner)
        try {
            val bots = mutableMapOf<String, List<PlayerId>>()
            runner.behave(
                request = { req ->
                    bots[req.requestId] = (1..req.count).map { i ->
                        val bot = lobby.spawnPlayer("Bot_${req.requestId.take(6)}_$i")
                        lobby.services.getOrThrow<IMatchmaker>().enqueue(bot.id, MatchmakingMode.SKIRMISH)
                        bot.id
                    }
                },
                release = { rel -> bots.remove(rel.requestId)?.forEach { lobby.detachPlayer(it) } },
            )
            val matchmaker = lobby.services.getOrThrow<IMatchmaker>()
            val human = lobby.spawnPlayer("human")

            lobby.click(human, "lobby.queue.bots")
            assertThat(runner.requests).hasSize(1)
            assertThat(runner.requests.single().count).isEqualTo(1)
            assertThat(matchmaker.pendingByMode(MatchmakingMode.SKIRMISH)).hasSize(2)

            // A second click while the first bots are on the way must not ask for another set.
            lobby.click(human, "lobby.queue.bots")
            assertThat(runner.requests).`as`("no stacked request").hasSize(1)

            // Cancel inside the queue countdown: the bots are released and nobody stays queued.
            lobby.step(60)
            lobby.click(human, "lobby.queue.leave")
            assertThat(runner.releases.map { it.requestId }).containsExactly(runner.requests.single().requestId)
            lobby.step(400)
            assertThat(matchmaker.pendingByMode(MatchmakingMode.SKIRMISH)).`as`("stale bots left the queue").isEmpty()
            assertThat(lobby.eventsOf<MatchStarted>()).isEmpty()

            // Next click brings exactly one bot again, not one more on top of a stale one.
            lobby.click(human, "lobby.queue.bots")
            assertThat(runner.requests).hasSize(2)
            assertThat(runner.requests.last().count).isEqualTo(1)
            assertThat(matchmaker.pendingByMode(MatchmakingMode.SKIRMISH)).hasSize(2)

            // The human disconnecting while queued releases the second set as well.
            lobby.detachPlayer(human.id)
            assertThat(runner.releases).hasSize(2)
            lobby.step(5)
            assertThat(matchmaker.pendingByMode(MatchmakingMode.SKIRMISH)).isEmpty()
        } finally {
            lobby.shutdown()
        }
    }
}
