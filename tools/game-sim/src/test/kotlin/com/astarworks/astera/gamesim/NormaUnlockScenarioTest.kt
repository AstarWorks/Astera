package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.SkillCastRequested
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.domain.event.NormaLevelUp
import com.astarworks.astera.match.domain.event.StarsCollected
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0289: a Norma level opens weapon rarities (`rarity_levels` of `content/norma-layer/layer.yaml`: common 1,
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
    fun `an epic weapon is refused at low Norma levels and works once its level is reached`() {
        scenario("norma-unlock-epic") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            red.holds("ember-gauntlet")
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"))
            advance(10)

            red.leftClick()
            red.drop()
            advance(4)
            assertThat(castsBy(red)).`as`("level 1: neither the attack nor the ultimate casts").isEmpty()
            assertThat(sim.normaPresentation.locked).`as`("the player is told epic opens at Lv 4").contains(Triple(red.id, 4, Rarity.EPIC))

            climb(matchId, red, levels = 2)
            red.leftClick()
            advance(4)
            assertThat(castsBy(red)).`as`("level 3: still locked").isEmpty()

            climb(matchId, red, levels = 1)
            assertThat(eventsOf<NormaLevelUp>().filter { it.playerId == red.id }.map { it.level }).containsExactly(2, 3, 4)
            val levelUp = sim.normaPresentation.levelUps.single { it.player == red.id && it.level == 4 }
            assertThat(levelUp.unlocked.map { it.rarity }).`as`("the level-up names the rarity it opened").containsExactly(Rarity.EPIC)

            red.leftClick()
            red.drop()
            advance(4)
            assertThat(castsBy(red).map { it.skillId.value }).`as`("level 4: attack and ultimate both cast")
                .contains("ember-gauntlet-s1", "ember-gauntlet-ego")
        }
    }

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
