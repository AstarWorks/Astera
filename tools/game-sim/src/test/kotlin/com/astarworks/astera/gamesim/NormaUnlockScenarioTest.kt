package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.SkillCastRequested
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.domain.event.NormaLevelUp
import com.astarworks.astera.match.domain.event.StarsCollected
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0292: a Norma level opens weapon rarities (`rarity_levels` of `content/norma-layer/layer.yaml`: common 1,
 * uncommon 2, rare 3, epic 4, legendary 5). An epic weapon (ember-gauntlet) does nothing - no attack, no skill - until
 * its holder reaches Lv 4, with a deny line naming the level and rarity; a common weapon works from Lv 1. The level-up
 * names the rarity it opened. Outside a match nothing is gated.
 */
class NormaUnlockScenarioTest {

    private fun ScenarioScope.climb(matchId: MatchId, who: SimEntity, levels: Int) {
        val stars = sim.services.getOrThrow<IMatchStarStore>()
        repeat(levels) {
            stars.add(matchId, who.id, 25)
            sim.eventBus.publish(StarsCollected(matchId, who.id, 25, stars.get(matchId, who.id)))
            advance(2)
        }
    }

    private fun ScenarioScope.castsBy(who: SimEntity) = eventsOf<SkillCastRequested>().filter { it.actorId == who.id }

    @Test
    fun `a common weapon works from level 1`() {
        scenario("norma-unlock-common") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            red.holds("knight-sword")
            startMatch("carry-tnt", mapOf(red to "red", blue to "blue"))
            advance(10)

            red.leftClick()
            advance(4)
            assertThat(castsBy(red)).`as`("common opens at Lv 1").isNotEmpty()
            assertThat(sim.normaPresentation.locked).isEmpty()
        }
    }

    @Test
    fun `outside a match nothing is gated`() {
        scenario("norma-unlock-no-match") {
            val red = player("red-1", team = "red")
            red.holds("ember-gauntlet")
            red.leftClick()
            advance(4)
            assertThat(castsBy(red)).`as`("no Norma match, no gate").isNotEmpty()
            assertThat(sim.normaPresentation.locked).isEmpty()
        }
    }
}
