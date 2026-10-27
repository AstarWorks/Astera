package com.astarworks.astera.gamesim

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

/**
 * MODE-15: the Norma layer (stars from stage generators, Norma levels that unlock weapon rarities; ADR-0292) is a standard layer of every
 * game mode, not a mode of its own. Played on the real `content/`: a siege objective mode and Annihilation both get
 * star pickups and level-ups, and the time limit ranks by the mode's own
 * objective before the Norma level.
 */
class NormaLayerScenarioTest {

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
    fun `carry-tnt - a generator pop is collected, stars level the player up and the top Norma level does not end the match`() {
        scenario("norma-layer-carry-tnt") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"))

            // The siege stage has its own star generators (siege-center-east at 14.5, 65, 0.5, stage-local).
            red.moveTo(Vec3(14.5, 65.0, 0.5))
            blue.moveTo(Vec3(80.0, 65.0, 0.5))
            advance(650)
            assertThat(eventsOf<StarsCollected>().filter { it.playerId == red.id }).`as`("red collected a pop in carry-tnt").isNotEmpty()
            assertThat(stars().get(matchId, red.id)).isGreaterThanOrEqualTo(1)

            // Level 1 -> 5 (norma_max_level): every level-up is announced; the top level never ends the match.
            climb(matchId, red, levels = 4)
            assertThat(eventsOf<NormaLevelUp>().filter { it.playerId == red.id }.map { it.level }).containsExactly(2, 3, 4, 5)
            advance(40)
            assertThat(sim.services.getOrThrow<IMatchRepository>().findById(matchId)!!.phase).isEqualTo(MatchPhase.ACTIVE)
            assertThat(eventsOf<MatchEnded>().filter { it.matchId == matchId }).isEmpty()
        }
    }

    @Test
    fun `annihilation - the layer runs there too, stars level up and the layer time limit stays off`() {
        scenario("norma-layer-annihilation") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow = player("yellow-1", team = "yellow")
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")

            // annihilation-center-east stands at 9.5, 65, 0.5 (stage-local) on the centre island.
            red.moveTo(Vec3(9.5, 65.0, 0.5))
            blue.moveTo(Vec3(40.0, 65.0, 23.0))
            yellow.moveTo(Vec3(-40.0, 65.0, 23.0))
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
    fun `time limit - the mode objective ranks before the Norma level`() {
        scenario("norma-layer-time-limit-objective") {
            val red = player("red-1", team = "red", at = Vec3(0.0, 65.0, -50.0))
            val blue = player("blue-1", team = "blue", at = Vec3(0.0, 65.0, -50.0))
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"), stage = "siege-isles-tnt")

            // Blue clears Norma level 1 (10 stars), red makes the only TNT delivery (count 66 vs 100).
            stars().add(matchId, blue.id, 10)
            sim.eventBus.publish(StarsCollected(matchId, blue.id, 10, 10))
            advance(2)
            assertThat(eventsOf<NormaLevelUp>().any { it.playerId == blue.id && it.level == 2 }).isTrue()

            red.moveTo(Vec3(0.0, 65.0, -6.0))
            advance(40)
            red.moveTo(Vec3(80.0, 65.0, 0.0))
            advance(40)
            advance(18_100)
            val ended = eventsOf<MatchEnded>().single { it.matchId == matchId }
            assertThat(ended.reason).`as`("blue leads on Norma level, but red leads on the mode objective").isEqualTo(EndReason.NormaTimeLimit(TeamId("red")))
        }
    }
}
