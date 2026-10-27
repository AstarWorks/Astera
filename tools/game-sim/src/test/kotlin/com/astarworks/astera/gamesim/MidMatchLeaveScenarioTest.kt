package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerStatsRepository
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.PlayerLeavingForLobby
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.SessionState
import com.astarworks.astera.match.application.port.outbound.IMatchNormaStore
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.domain.event.NormaLevelUp
import com.astarworks.astera.match.domain.event.PlayerForfeited
import com.astarworks.astera.match.domain.event.PlayerReconnectedToMatch
import com.astarworks.astera.match.domain.event.StarsCollected
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * MODE-14: leaving a running match bursts the carried stars where the player stands. A dropped connection holds the
 * seat for 60 s (back within it = same match, team, Norma level), and after it the player forfeits; a deliberate leave
 * (`/astera travel lobby`, F-menu Leave) forfeits at once. A forfeit means: out of the roster, a loss on the record, the match goes on or ends when their team is empty.
 */
class MidMatchLeaveScenarioTest {

    private val windowTicks = 1200

    @Test
    fun `a drop bursts the stars at the leaver and a return within the window restores team and norma level`() {
        scenario("leave-rejoin-in-window", standaloneTeams = false) {
            val red = player("red", team = "red")
            val blue = player("blue", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"))
            val stars = sim.services.getOrThrow<IMatchStarStore>()
            val norma = sim.services.getOrThrow<IMatchNormaStore>()
            val sessions = sim.services.getOrThrow<IPlayerSessionRepository>()
            val matches = sim.services.getOrThrow<IMatchRepository>()

            // Red clears level 1 (stars are kept) and carries 20 stars at a spot of its own.
            val spot = Vec3(-30.0, 65.0, 4.0)
            red.moveTo(spot)
            stars.add(matchId, red.id, 20)
            sim.eventBus.publish(StarsCollected(matchId, red.id, 20, stars.get(matchId, red.id)))
            advance(2)
            assertThat(eventsOf<NormaLevelUp>().any { it.playerId == red.id && it.level == 2 }).isTrue()
            val carried = stars.get(matchId, red.id)
            val worldBefore = red.world
            assertThat(carried).isGreaterThanOrEqualTo(20)
            val lyingBefore = sim.starDrops.lying().sumOf { it.amount }

            sim.detachPlayer(red.id)

            assertThat(sessions.find(red.id)?.state).isEqualTo(SessionState.Disconnected)
            assertThat(stars.get(matchId, red.id)).`as`("the leaver carries nothing").isZero()
            val burst = sim.starDrops.lying().filter { it.world == worldBefore }
            assertThat(burst.sumOf { it.amount } - lyingBefore).`as`("every carried star lies on the map").isEqualTo(carried)
            assertThat(burst.filter { it.position.distance(spot) < 4.0 }).`as`("they burst where the player stood").isNotEmpty()

            advance(windowTicks / 2)
            assertThat(matches.findById(matchId)?.phase).isEqualTo(MatchPhase.ACTIVE)
            assertThat(matches.findById(matchId)?.teams?.flatMap { it.members }).`as`("the seat is held").contains(red.id)

            sim.attachPlayer(red.id, "red")
            advance(5)

            assertThat(eventsOf<PlayerReconnectedToMatch>().map { it.playerId }).containsExactly(red.id)
            assertThat(sessions.find(red.id)?.state).isEqualTo(SessionState.InMatch)
            assertThat(matches.findById(matchId)?.teams?.first { red.id in it.members }?.id).isEqualTo(TeamId("red"))
            assertThat(norma.get(matchId, red.id).level).`as`("norma level kept").isEqualTo(2)

            // The window is spent for good: nothing forfeits later.
            advance(windowTicks)
            assertThat(eventsOf<PlayerForfeited>()).isEmpty()
            assertThat(matches.findById(matchId)?.phase).isEqualTo(MatchPhase.ACTIVE)
            assertThat(matches.findById(matchId)?.teams?.flatMap { it.members }).contains(red.id)
        }
    }

    @Test
    fun `without a return the leaver forfeits after the window and the last member's team loses the match`() {
        scenario("leave-forfeit-1v1", standaloneTeams = false) {
            val red = player("red", team = "red")
            val blue = player("blue", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"))
            val sessions = sim.services.getOrThrow<IPlayerSessionRepository>()
            val stats = sim.services.getOrThrow<IPlayerStatsRepository>()

            sim.detachPlayer(red.id)
            advance(windowTicks - 40)
            assertThat(eventsOf<PlayerForfeited>()).`as`("still inside the window").isEmpty()
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            advance(80)
            assertThat(eventsOf<PlayerForfeited>().map { it.playerId }).containsExactly(red.id)
            assertThat(sessions.find(red.id)?.state).isEqualTo(SessionState.Lobby)
            val ended = eventsOf<MatchEnded>().single { it.matchId == matchId }
            assertThat(ended.winningTeam).`as`("the team that stayed wins").isEqualTo(TeamId("blue"))
            assertThat(ended.reason).isEqualTo(EndReason.TeamVictory(TeamId("blue")))
            assertThat(stats.find(red.id)?.losses).`as`("a forfeit counts as a loss").isEqualTo(1L)
            assertThat(stats.find(red.id)?.wins ?: 0L).isZero()
            assertThat(stats.find(blue.id)?.wins).isEqualTo(1L)
        }
    }

    @Test
    fun `a forfeit leaves the roster and the match goes on while the team still has members`() {
        scenario("leave-forfeit-2v2", standaloneTeams = false) {
            val r1 = player("red-1", team = "red")
            val r2 = player("red-2", team = "red")
            val b1 = player("blue-1", team = "blue")
            val b2 = player("blue-2", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(r1 to "red", r2 to "red", b1 to "blue", b2 to "blue"))
            val matches = sim.services.getOrThrow<IMatchRepository>()

            sim.detachPlayer(r1.id)
            advance(windowTicks + 20)

            assertThat(eventsOf<PlayerForfeited>().map { it.playerId }).containsExactly(r1.id)
            assertThat(matches.findById(matchId)?.phase).`as`("the match continues").isEqualTo(MatchPhase.ACTIVE)
            val roster = matches.findById(matchId)?.teams?.flatMap { it.members }.orEmpty()
            assertThat(roster).doesNotContain(r1.id).contains(r2.id, b1.id, b2.id)
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            // The second red member goes too: the team is empty, blue wins.
            sim.detachPlayer(r2.id)
            advance(windowTicks + 20)
            assertThat(eventsOf<MatchEnded>().single().winningTeam).isEqualTo(TeamId("blue"))
        }
    }

    @Test
    fun `leaving for the lobby by command forfeits at once and bursts the stars in the arena`() {
        scenario("leave-by-command", standaloneTeams = false) {
            val red = player("red", team = "red")
            val blue = player("blue", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"))
            val stars = sim.services.getOrThrow<IMatchStarStore>()
            val sessions = sim.services.getOrThrow<IPlayerSessionRepository>()

            val spot = Vec3(-30.0, 65.0, -6.0)
            red.moveTo(spot)
            stars.add(matchId, red.id, 4)
            val world = red.world

            sim.eventBus.publish(PlayerLeavingForLobby(red.id))

            assertThat(eventsOf<PlayerForfeited>().map { it.playerId }).`as`("no rejoin grace: forfeited at once").containsExactly(red.id)
            assertThat(sessions.find(red.id)?.state).isNotEqualTo(SessionState.Disconnected)
            assertThat(stars.get(matchId, red.id)).isZero()
            assertThat(sim.starDrops.lying().filter { it.world == world && it.position.distance(spot) < 4.0 }.sumOf { it.amount })
                .`as`("the stars burst at the arena position, not at the lobby").isEqualTo(4)

            advance(windowTicks + 20)
            assertThat(eventsOf<PlayerForfeited>()).`as`("forfeited once, not again after the old window").hasSize(1)
        }
    }
}
