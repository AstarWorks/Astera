package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.ISpawnProtectionGateway
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.PlayerRespawned
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.SessionState
import com.astarworks.astera.core.domain.proxy.ProxyChannel
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.core.domain.proxy.ServerRole
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.domain.event.PlayerReconnectedToMatch
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.domain.event.StarsCollected
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Game-loop gaps found in the founder playtest: a player who drops out of a running match comes back to their
 * team (also across the lobby / game split, where Velocity lands every login on the lobby), the Norma sidebar
 * and result name the goal / the real reason, and spawn protection actually protects.
 */
class MidMatchReconnectScenarioTest {

    private val lobbyName = ServerName("lobby")
    private val gameName = ServerName("game")

    private fun arrived(network: SimProxyNetwork, sim: GameSim, player: PlayerId, maxTicks: Int): Boolean {
        repeat(maxTicks) {
            if (sim.world.find(player) != null) return true
            network.step(1)
        }
        return sim.world.find(player) != null
    }

    private class Split(val network: SimProxyNetwork, val lobby: GameSim, val game: GameSim) {
        fun shutdown() {
            lobby.shutdown()
            game.shutdown()
        }
    }

    private fun boot(name: String): Split {
        val network = SimProxyNetwork()
        val lobby = GameSim.boot("$name-lobby", role = ServerRole.LOBBY, serverName = lobbyName, proxy = network.node(lobbyName))
        val game = GameSim.boot("$name-game", role = ServerRole.GAME, serverName = gameName, proxy = network.node(gameName))
        network.register(lobbyName, lobby)
        network.register(gameName, game)
        return Split(network, lobby, game)
    }

    /** Queues two players on the lobby and plays until the hosted match is ACTIVE on the game server. */
    private fun activeMatch(split: Split, red: PlayerId, blue: PlayerId): MatchId {
        val matchmaker = split.lobby.services.getOrThrow<IMatchmaker>()
        matchmaker.enqueue(red, MatchmakingMode.Norma)
        matchmaker.enqueue(blue, MatchmakingMode.Norma)
        assertThat(arrived(split.network, split.game, red, 800)).isTrue()
        assertThat(arrived(split.network, split.game, blue, 200)).isTrue()
        split.network.step(5)
        val matches = split.game.services.getOrThrow<IMatchRepository>()
        val matchId = matches.findActiveMatches().single().id
        var active = false
        repeat(20 * 30) {
            if (!active) {
                split.network.step(1)
                active = matches.findById(matchId)?.phase == MatchPhase.ACTIVE
            }
        }
        assertThat(active).`as`("hosted match is ACTIVE").isTrue()
        return matchId
    }

    private fun teamOf(game: GameSim, matchId: MatchId, player: PlayerId): TeamId =
        checkNotNull(game.services.getOrThrow<IMatchRepository>().findById(matchId)).teams.first { player in it.members }.id

    // ---- 1. reconnect ------------------------------------------------------------------------------------------

    @Test
    fun `a player who drops out of the running match is routed from the lobby back onto their team`() {
        val split = boot("reconnect-split")
        try {
            val red = split.lobby.spawnPlayer("red")
            val blue = split.lobby.spawnPlayer("blue")
            val matchId = activeMatch(split, red.id, blue.id)
            val game = split.game
            val matches = game.services.getOrThrow<IMatchRepository>()
            val sessions = game.services.getOrThrow<IPlayerSessionRepository>()
            val team = teamOf(game, matchId, red.id)

            // The connection drops (the proxy tells the game backend the player is gone).
            game.detachPlayer(red.id)
            split.network.pump()
            assertThat(split.network.sent.map { it.channel }).`as`("the lobby is told a seat is held").contains(ProxyChannel.MATCH_RECONNECT)
            assertThat(sessions.find(red.id)?.state).isEqualTo(SessionState.Disconnected)
            assertThat(matches.findById(matchId)?.phase).`as`("the match goes on without them").isEqualTo(MatchPhase.ACTIVE)

            // Velocity lands the new login on the lobby; the lobby sends it straight on to the running match.
            split.lobby.attachPlayer(red.id, "red")
            var back = false
            repeat(400) {
                if (!back) {
                    split.network.step(1)
                    back = game.world.find(red.id) != null
                }
            }
            split.network.step(5)

            assertThat(back).`as`("routed to the game server").isTrue()
            assertThat(split.lobby.world.find(red.id)).`as`("not left in the lobby").isNull()
            assertThat(split.network.moves.last()).isEqualTo(Triple(red.id, lobbyName, gameName))
            assertThat(game.eventsOf<PlayerReconnectedToMatch>().map { it.playerId }).containsExactly(red.id)
            assertThat(sessions.find(red.id)?.state).isEqualTo(SessionState.InMatch)
            assertThat(sessions.find(red.id)?.currentMatch).isEqualTo(matchId)
            assertThat(teamOf(game, matchId, red.id)).`as`("same team as before").isEqualTo(team)
            assertThat(matches.findById(matchId)?.phase).isEqualTo(MatchPhase.ACTIVE)
            assertThat(game.eventsOf<MatchEnded>()).isEmpty()

            // Back on their own side of the arena, protected like after a respawn.
            val stage = checkNotNull(game.services.getOrThrow<IStageRegistry>().find(checkNotNull(matches.findById(matchId)).stageId))
            val position = checkNotNull(game.world.find(red.id)).position
            assertThat(stage.spawnPoints.filter { it.team == team }.map { it.position })
                .`as`("placed at a spawn of their team").anyMatch { it.x == position.x && it.z == position.z }
            assertThat(game.services.getOrThrow<ISpawnProtectionGateway>().isProtected(red.id)).isTrue()
        } finally {
            split.shutdown()
        }
    }

    @Test
    fun `a login after the match ended is not sent back to the game server`() {
        val split = boot("reconnect-ended")
        try {
            val red = split.lobby.spawnPlayer("red")
            val blue = split.lobby.spawnPlayer("blue")
            val matchId = activeMatch(split, red.id, blue.id)

            split.game.detachPlayer(red.id)
            split.network.pump()
            split.game.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.AdminEnded)
            split.network.step(10)

            val movesBefore = split.network.moves.size
            split.lobby.attachPlayer(red.id, "red")
            split.network.step(100)
            assertThat(split.lobby.world.find(red.id)).`as`("stays in the lobby").isNotNull()
            assertThat(split.game.world.find(red.id)).isNull()
            assertThat(split.network.moves.drop(movesBefore)).`as`("no hop to the game server").isEmpty()
        } finally {
            split.shutdown()
        }
    }

    @Test
    fun `on a single server a rejoining participant is put back on their team with the match sidebar`() {
        scenario("reconnect-single-server", standaloneTeams = false) {
            val red = player("red", team = "red")
            val blue = player("blue", team = "blue")
            val matchId = startMatch("norma", mapOf(red to "red", blue to "blue"))
            val sessions = sim.services.getOrThrow<IPlayerSessionRepository>()
            val protection = sim.services.getOrThrow<ISpawnProtectionGateway>()

            sim.detachPlayer(red.id)
            assertThat(sessions.find(red.id)?.state).isEqualTo(SessionState.Disconnected)

            advance(40)
            sim.attachPlayer(red.id, "red")
            advance(5)

            assertThat(eventsOf<PlayerReconnectedToMatch>().map { it.playerId }).containsExactly(red.id)
            assertThat(sessions.find(red.id)?.state).isEqualTo(SessionState.InMatch)
            assertThat(protection.isProtected(red.id)).isTrue()
            val sidebar = checkNotNull(sim.hud.sidebar(red.id)) { "no sidebar after the rejoin" }
            assertThat(sidebar.titleKey.value).`as`("match sidebar, not the lobby one").isEqualTo("astera.gamemode.norma.title")
            assertThat(sim.match.matches.findById(matchId)?.teams?.first { red.id in it.members }?.id).isEqualTo(TeamId("red"))
        }
    }

    // ---- 2. Norma sidebar and result -----------------------------------------------------------------------------

    @Test
    fun `norma sidebar shows the top level and kills, and only the top Norma level wins`() {
        scenario("norma-goal-and-reason", standaloneTeams = false) {
            val red = player("red", team = "red")
            val blue = player("blue", team = "blue")
            val matchId = startMatch("norma", mapOf(red to "red", blue to "blue"))
            advance(25)

            val lines = sim.hud.sidebarText(red.id)
            assertThat(lines).`as`("goal line").anyMatch { it.startsWith("astera.hud.sidebar.norma.goal") && "max=5" in it }
            assertThat(lines.filter { it.startsWith("astera.hud.sidebar.norma.team") })
                .`as`("one kill line per team").hasSize(2).allMatch { "score=0" in it }

            // Kills alone never end the match: twenty kills by red leave it running (no kill-count victory).
            val bus = sim.services.getOrThrow<IDomainEventBus>()
            repeat(20) { bus.publish(PlayerKilled(blue.id, red.id, DamageSource.Environment.Fall(1.0), sim.tick)) }
            advance(20)
            assertThat(eventsOf<MatchEnded>().none { it.matchId == matchId }).`as`("20 kills ended the match").isTrue()
            assertThat(sim.match.matches.findById(matchId)?.score?.valueFor(TeamId("red"))).`as`("kills still counted").isEqualTo(20)

            // Reaching the top Norma level ends it with the Norma level reason.
            val stars = sim.services.getOrThrow<IMatchStarStore>()
            stars.add(matchId, red.id, 100)
            repeat(4) {
                bus.publish(StarsCollected(matchId, red.id, 1, stars.get(matchId, red.id)))
                advance(2)
            }
            advance(20)
            val ended = eventsOf<MatchEnded>().single { it.matchId == matchId }
            assertThat(ended.reason).isEqualTo(EndReason.NormaLevelReached(TeamId("red"), 5))
            assertThat(ended.winningTeam).isEqualTo(TeamId("red"))
            val chat = checkNotNull(sim.server.view(red.id)).messages
            assertThat(chat).anyMatch { "reached Norma level 5" in it }
            assertThat(chat).noneMatch { "objective completion" in it }
        }
    }

    // ---- 3. spawn protection ---------------------------------------------------------------------------------------

    private fun hit(target: PlayerId, attacker: PlayerId) = DamageAttempt(
        target,
        DamageProfile.single(1.0, DamageAttribute.PHYSICAL),
        DamageSource.Skill(SkillId("frost-nova"), attacker),
    )

    @Test
    fun `spawn protection blocks damage until it runs out`() {
        scenario("spawn-protection-enforced", standaloneTeams = false) {
            val red = player("red", team = "red")
            val blue = player("blue", team = "blue")
            startMatch("norma", mapOf(red to "red", blue to "blue"))
            val damage = checkNotNull(sim.combat.applyDamage)
            val protection = sim.services.getOrThrow<ISpawnProtectionGateway>()
            val before = hp(blue)

            protection.grantProtection(blue.id, 3)
            assertThat(damage.execute(hit(blue.id, red.id))).`as`("protected target").isInstanceOf(Result.Failure::class.java)
            assertThat(hp(blue)).isEqualTo(before)

            advance(3 * 20 + 2)
            assertThat(protection.isProtected(blue.id)).isFalse()
            assertThat(damage.execute(hit(blue.id, red.id))).`as`("protection over").isInstanceOf(Result.Success::class.java)
            assertThat(hp(blue)).isLessThan(before)
        }
    }

    @Test
    fun `a respawned player cannot be hurt during the protection window`() {
        scenario("spawn-protection-after-respawn", standaloneTeams = false) {
            val red = player("red", team = "red")
            val blue = player("blue", team = "blue")
            startMatch("norma", mapOf(red to "red", blue to "blue"))
            val damage = checkNotNull(sim.combat.applyDamage)

            sim.services.getOrThrow<IDomainEventBus>()
                .publish(PlayerKilled(blue.id, red.id, DamageSource.Environment.Fall(1.0), sim.tick))
            assertThat(advanceUntil(20 * 12) { eventsOf<PlayerRespawned>().any { it.playerId == blue.id } }).isTrue()

            val full = hp(blue)
            assertThat(damage.execute(hit(blue.id, red.id))).`as`("just respawned").isInstanceOf(Result.Failure::class.java)
            assertThat(hp(blue)).isEqualTo(full)
            advance(20 * 4)
            assertThat(damage.execute(hit(blue.id, red.id))).`as`("window over").isInstanceOf(Result.Success::class.java)
        }
    }
}
