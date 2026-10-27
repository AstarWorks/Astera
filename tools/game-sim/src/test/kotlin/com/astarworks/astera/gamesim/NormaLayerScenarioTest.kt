package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.domain.event.NormaLevelUp
import com.astarworks.astera.match.domain.event.StarsCollected
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * MODE-15: the Norma layer (stars from stage generators, Norma levels, the top level wins) is a standard layer of every
 * game mode, not a mode of its own. Played on the real `content/`: a siege objective mode and Annihilation both get
 * star pickups and level-ups, `norma_win: false` switches the win rule off, and the time limit ranks by the mode's own
 * objective before the Norma level.
 */
class NormaLayerScenarioTest {

    @TempDir
    lateinit var temp: Path

    private fun ScenarioScope.stars() = sim.services.getOrThrow<IMatchStarStore>()

    /** Clears every level for [who]: carries enough stars and signals the pickup, once per level up to [levels]. */
    private fun ScenarioScope.climb(matchId: MatchId, who: SimEntity, levels: Int) {
        repeat(levels) {
            stars().add(matchId, who.id, 25)
            sim.eventBus.publish(StarsCollected(matchId, who.id, 25, stars().get(matchId, who.id)))
            advance(2)
        }
    }

    @Test
    fun `break-core - a generator pop is collected, stars level the player up and the top Norma level wins next to the objective`() {
        scenario("norma-layer-break-core") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val matchId = startMatch("break-core", mapOf(red to "red", blue to "blue"))

            // The siege stage has its own star generators (siege-center-east at 11.5, 65, 0.5, stage-local).
            red.moveTo(Vec3(11.5, 65.0, 0.5))
            blue.moveTo(Vec3(60.0, 65.0, 0.5))
            advance(650)
            assertThat(eventsOf<StarsCollected>().filter { it.playerId == red.id }).`as`("red collected a pop in break-core").isNotEmpty()
            assertThat(stars().get(matchId, red.id)).isGreaterThanOrEqualTo(1)

            // Level 1 -> 5 (norma_max_level): every level-up is announced, the last one ends the match for red.
            climb(matchId, red, levels = 4)
            assertThat(eventsOf<NormaLevelUp>().filter { it.playerId == red.id }.map { it.level }).containsExactly(2, 3, 4, 5)
            val ended = eventsOf<MatchEnded>().single { it.matchId == matchId }
            assertThat(ended.reason).isEqualTo(EndReason.NormaLevelReached(TeamId("red"), 5))
            assertThat(ended.winningTeam).isEqualTo(TeamId("red"))
        }
    }

    @Test
    fun `annihilation - the layer runs there too, stars level up and the layer time limit stays off`() {
        scenario("norma-layer-annihilation") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow = player("yellow-1", team = "yellow")
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")

            // annihilation-center-east stands at 14.5, 65, 0.5 (stage-local) on the centre island.
            red.moveTo(Vec3(14.5, 65.0, 0.5))
            blue.moveTo(Vec3(92.0, 65.0, 53.0))
            yellow.moveTo(Vec3(-92.0, 65.0, 53.0))
            advance(650)
            assertThat(eventsOf<StarsCollected>().filter { it.playerId == red.id }).`as`("red collected a pop in annihilation").isNotEmpty()

            climb(matchId, red, levels = 1)
            assertThat(eventsOf<NormaLevelUp>().any { it.playerId == red.id && it.level == 2 }).isTrue()
            val shown = sim.normaPresentation.levelUps.filter { it.player == red.id && it.level == 2 }
            assertThat(shown).`as`("the level-up is presented once, like in every mode").hasSize(1)

            // Annihilation keeps its own clock: the layer's 10 minute limit does not cut it short.
            advance(12_100)
            assertThat(eventsOf<MatchEnded>().filter { it.matchId == matchId }.map { it.reason })
                .noneMatch { it is EndReason.NormaTimeLimit }
        }
    }

    @Test
    fun `norma_win false - the top Norma level is reached but does not end the match`() {
        val content = Files.createDirectory(temp.resolve("content"))
        Files.list(GameSim.defaultContentDir()).use { entries ->
            entries.filter { it.fileName.toString() != "norma-layer" }.forEach { Files.createSymbolicLink(content.resolve(it.fileName), it) }
        }
        val layer = Files.createDirectory(content.resolve("norma-layer"))
        val shipped = Files.readString(GameSim.defaultContentDir().resolve("norma-layer/layer.yaml"))
        assertThat(shipped).contains("norma_win: \"true\"")
        Files.writeString(layer.resolve("layer.yaml"), shipped.replace("norma_win: \"true\"", "norma_win: \"false\""))

        val sim = GameSim.boot("norma-layer-no-win", contentDir = content)
        try {
            with(ScenarioScope(sim)) {
                val red = player("red-1", team = "red")
                val blue = player("blue-1", team = "blue")
                val matchId = startMatch("break-core", mapOf(red to "red", blue to "blue"))
                climb(matchId, red, levels = 4)
                assertThat(eventsOf<NormaLevelUp>().filter { it.playerId == red.id }.map { it.level }).containsExactly(2, 3, 4, 5)
                advance(40)
                assertThat(sim.services.getOrThrow<IMatchRepository>().findById(matchId)!!.phase).`as`("norma_win false: the match runs on").isEqualTo(MatchPhase.ACTIVE)
                assertThat(eventsOf<MatchEnded>().filter { it.matchId == matchId }).isEmpty()
            }
        } finally {
            sim.writeTrace()
            sim.shutdown()
        }
    }

    @Test
    fun `time limit - the mode objective ranks before the Norma level`() {
        scenario("norma-layer-time-limit-objective") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val matchId = startMatch("battle-loyal", mapOf(red to "red", blue to "blue"))

            // Blue clears Norma level 1 (10 stars, then bursts half of them on death), red scores the only battle-loyal point.
            stars().add(matchId, blue.id, 10)
            sim.eventBus.publish(StarsCollected(matchId, blue.id, 10, 10))
            advance(2)
            assertThat(eventsOf<NormaLevelUp>().any { it.playerId == blue.id && it.level == 2 }).isTrue()

            sim.setMaxHealth(blue, 1.0)
            red.moveTo(blue.position + Vec3(0.0, 0.0, 6.0))
            red.holds("pistol")
            red.faces(blue)
            repeat(20) {
                if (eventsOf<PlayerKilled>().none { it.victim == blue.id }) {
                    red.rightClick()
                    advance(10)
                }
            }
            assertThat(eventsOf<PlayerKilled>().any { it.victim == blue.id }).isTrue()
            assertThat(sim.services.getOrThrow<IMatchRepository>().findById(matchId)!!.score.valueFor(TeamId("red"))).isEqualTo(1)

            advance(12_100)
            val ended = eventsOf<MatchEnded>().single { it.matchId == matchId }
            assertThat(ended.reason).`as`("blue leads on Norma level, but red leads on the mode objective").isEqualTo(EndReason.NormaTimeLimit(TeamId("red")))
        }
    }
}
