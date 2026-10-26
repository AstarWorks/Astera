package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.TeamId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * MODE-05: carry-mini-tnt is played on the real `siege-isles-mini-tnt` stage and `content/game-modes/carry-mini-tnt.yaml`
 * (10 pieces to win, carry up to 4, pieces regrow after 15 s). Pickup spots are the stage's wool-a/b/c and tnt-spawn zones (stage-local coordinates).
 */
class CarryMiniTntScenarioTest {

    private val spots = listOf(
        Vec3(0.0, 65.0, -95.0), // wool-a
        Vec3(0.0, 65.0, 8.0), // wool-b
        Vec3(0.0, 65.0, 95.0), // wool-c
        Vec3(0.0, 65.0, -8.0), // tnt-spawn
    )
    private val blueBase = Vec3(163.0, 65.0, 0.0)
    private val redBase = Vec3(-163.0, 65.0, 0.0)
    private val nowhere = Vec3(0.0, 65.0, -50.0)

    private fun ScenarioScope.markers(): List<String?> = spots.map { blockAt(it.copy(y = 64.0)) }

    @Test
    fun `carry mini tnt - pickups are taken, delivered for points, regrow, and enough deliveries win`() {
        scenario("siege-carry-mini-tnt") {
            val runner = player("runner", at = nowhere)
            val guard = player("guard", at = nowhere)
            val matchId = startMatch("carry-mini-tnt", mapOf(runner to "red", guard to "blue"))
            assertThat(markers()).`as`("a mini-TNT lies on every spot").containsOnly("minecraft:tnt")

            // Delivering with empty hands scores nothing.
            guard.moveTo(redBase)
            guard.moveTo(nowhere)
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            // Cycle 1: pick up all four pieces, deliver 4/10.
            for (spot in spots) runner.moveTo(spot)
            assertThat(markers()).`as`("taken pieces leave the spots").doesNotContain("minecraft:tnt")
            runner.moveTo(blueBase)
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            // The pieces regrow after 15 s.
            runner.moveTo(nowhere)
            advance(20 * 17)
            assertThat(markers()).`as`("pieces regrew").containsOnly("minecraft:tnt")

            // Cycle 2 (8/10) and cycle 3 (12 >= 10) win it for red.
            repeat(2) {
                for (spot in spots) runner.moveTo(spot)
                runner.moveTo(blueBase)
                if (it == 0) {
                    assertThat(eventsOf<MatchEnded>()).`as`("8/10 is not a win").isEmpty()
                    runner.moveTo(nowhere)
                    advance(20 * 17)
                }
            }
            val end = eventsOf<MatchEnded>().single()
            assertThat(end.matchId).isEqualTo(matchId)
            assertThat(end.winningTeam).isEqualTo(TeamId("red"))
            assertThat(markers()).`as`("markers removed").doesNotContain("minecraft:tnt")
        }
    }
}
