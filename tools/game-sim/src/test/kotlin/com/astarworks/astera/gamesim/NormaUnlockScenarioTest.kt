package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.SkillCastRequested
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.SkillTrigger
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.domain.event.NormaLevelUp
import com.astarworks.astera.match.domain.event.StarsCollected
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0288: Norma levels are in-match power progression. An ultimate (the `drop` EGO binding, `min_norma_level: 3`)
 * stays locked below its level: no cast, a deny line to the player. Climbing to the level opens it, and the level-up
 * names what it unlocked. Outside a match nothing is gated.
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

    private fun ScenarioScope.ultimateCasts(who: SimEntity) =
        eventsOf<SkillCastRequested>().filter { it.actorId == who.id && it.skillId == SkillId("bastion-greatsword-ego") }

    @Test
    fun `a locked ultimate is refused with a hint and opens once the Norma level is reached`() {
        scenario("norma-unlock-ultimate") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            red.holds("bastion-greatsword")
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"))
            advance(10)

            red.drop()
            advance(4)
            assertThat(ultimateCasts(red)).`as`("level 1: the ultimate does not cast").isEmpty()
            assertThat(sim.normaPresentation.locked).`as`("the player is told it unlocks at Lv 3").contains(red.id to 3)

            climb(matchId, red, levels = 1)
            red.drop()
            advance(4)
            assertThat(ultimateCasts(red)).`as`("level 2: still locked").isEmpty()

            climb(matchId, red, levels = 1)
            assertThat(eventsOf<NormaLevelUp>().filter { it.playerId == red.id }.map { it.level }).containsExactly(2, 3)
            val levelUp = sim.normaPresentation.levelUps.single { it.player == red.id && it.level == 3 }
            assertThat(levelUp.unlocked.map { it.trigger }).`as`("the level-up names the ultimate it opened").containsExactly(SkillTrigger.DROP)

            red.drop()
            advance(4)
            assertThat(ultimateCasts(red)).`as`("level 3: the ultimate casts").isNotEmpty()
        }
    }

    @Test
    fun `outside a match nothing is gated`() {
        scenario("norma-unlock-no-match") {
            val red = player("red-1", team = "red")
            red.holds("bastion-greatsword")
            red.drop()
            advance(4)
            assertThat(ultimateCasts(red)).`as`("no Norma match, no gate").isNotEmpty()
            assertThat(sim.normaPresentation.locked).isEmpty()
        }
    }
}
