package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.TeamId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * MODE-05: carry-mini-tnt is played on the real `siege-isles-mini-tnt` stage and `content/game-modes/carry-mini-tnt.yaml`:
 * pieces from the wool-a/b/c spots are carried into the ENEMY base and planted there (the own base plants nothing);
 * the team that plants 10 pieces blows the enemy base up and wins. Stage-local coordinates.
 */
class CarryMiniTntScenarioTest {

    private val pickupSpots = listOf(
        Vec3(0.0, 65.0, -82.0), // wool-a
        Vec3(0.0, 65.0, 8.0), // wool-b
        Vec3(0.0, 65.0, 82.0), // wool-c
    )
    private val blueBase = Vec3(107.0, 65.0, 0.0)
    private val redBase = Vec3(-107.0, 65.0, 0.0)
    private val nowhere = Vec3(0.0, 65.0, -50.0)

    private fun ScenarioScope.markers(): List<String?> = pickupSpots.map { blockAt(it.copy(y = 64.0)) }

    /** Takes all three pieces, shows the own base plants none of them, plants them in the enemy base and waits for the spots to regrow. */
    private fun ScenarioScope.collectAndPlant(runner: SimEntity, home: Vec3, enemyBase: Vec3) {
        for (spot in pickupSpots) runner.moveTo(spot)
        assertThat(markers()).`as`("taken pieces leave the spots").doesNotContain("minecraft:tnt")
        runner.moveTo(home)
        assertThat(eventsOf<MatchEnded>()).`as`("the own base is not a target").isEmpty()
        runner.moveTo(enemyBase)
        runner.moveTo(nowhere)
        advance(20 * 17)
        assertThat(markers()).`as`("pieces regrew").containsOnly("minecraft:tnt")
    }

    @Test
    fun `carry mini tnt - pieces planted in the enemy base blow it up`() {
        scenario("siege-carry-mini-tnt") {
            val runner = player("runner", at = nowhere)
            val guard = player("guard", at = nowhere)
            val matchId = startMatch("carry-mini-tnt", mapOf(runner to "red", guard to "blue"), stage = "siege-isles-mini-tnt")
            assertThat(markers()).`as`("a mini-TNT lies on every spot").containsOnly("minecraft:tnt")

            // Entering a base with empty hands plants nothing.
            guard.moveTo(redBase)
            guard.moveTo(nowhere)
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            // Trips 1-3 plant 3, 6, 9 pieces: below the goal of 10 nothing ends.
            repeat(3) { collectAndPlant(runner, redBase, blueBase) }
            assertThat(eventsOf<MatchEnded>()).`as`("9/10 planted: not blown up yet").isEmpty()

            // Trip 4 takes the plant count to 12 >= 10: the enemy base blows up and red wins.
            for (spot in pickupSpots) runner.moveTo(spot)
            runner.moveTo(blueBase)
            val end = eventsOf<MatchEnded>().single()
            assertThat(end.matchId).isEqualTo(matchId)
            assertThat(end.winningTeam).isEqualTo(TeamId("red"))
            assertThat(markers()).`as`("markers removed").doesNotContain("minecraft:tnt")
        }
    }
}
