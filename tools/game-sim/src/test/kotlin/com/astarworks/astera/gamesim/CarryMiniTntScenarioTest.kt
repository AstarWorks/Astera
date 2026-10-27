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
        Vec3(0.0, 65.0, -62.0), // wool-a
        Vec3(0.0, 65.0, 6.0), // wool-b
        Vec3(0.0, 65.0, 62.0), // wool-c
    )
    private val blueBase = Vec3(85.0, 65.0, 0.0)
    private val redBase = Vec3(-85.0, 65.0, 0.0)
    private val nowhere = Vec3(0.0, 65.0, -50.0)

    /** Walks [who] to [at] and stays long enough for the pickup / plant hold (15 ticks) to complete. */
    private fun ScenarioScope.hold(who: SimEntity, at: Vec3, ticks: Int = 40) {
        who.moveTo(at)
        advance(ticks)
    }

    private fun ScenarioScope.markers(): List<String?> = pickupSpots.map { blockAt(it.copy(y = 64.0)) }

    /** Takes all three pieces, shows the own base plants none of them, plants them in the enemy base and waits for the spots to regrow. */
    private fun ScenarioScope.collectAndPlant(runner: SimEntity, home: Vec3, enemyBase: Vec3) {
        for (spot in pickupSpots) hold(runner, spot)
        assertThat(markers()).`as`("taken pieces leave the spots").doesNotContain("minecraft:tnt")
        hold(runner, home)
        assertThat(eventsOf<MatchEnded>()).`as`("the own base is not a target").isEmpty()
        hold(runner, enemyBase)
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
            hold(guard, redBase)
            guard.moveTo(nowhere)
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            // Trips 1-3 plant 3, 6, 9 pieces: below the goal of 10 nothing ends.
            repeat(3) { collectAndPlant(runner, redBase, blueBase) }
            assertThat(eventsOf<MatchEnded>()).`as`("9/10 planted: not blown up yet").isEmpty()

            // Trip 4 takes the plant count to 12 >= 10: the enemy base blows up and red wins.
            for (spot in pickupSpots) hold(runner, spot)
            hold(runner, blueBase)
            val end = eventsOf<MatchEnded>().single()
            assertThat(end.matchId).isEqualTo(matchId)
            assertThat(end.winningTeam).isEqualTo(TeamId("red"))
            assertThat(markers()).`as`("markers removed").doesNotContain("minecraft:tnt")
        }
    }

    @Test
    fun `carry mini tnt - a dead carrier drops a pile that anyone can pick up and plant`() {
        scenario("siege-carry-mini-tnt-drop") {
            val runner = player("runner", at = nowhere)
            val guard = player("guard", at = nowhere)
            val matchId = startMatch("carry-mini-tnt", mapOf(runner to "red", guard to "blue"), stage = "siege-isles-mini-tnt")
            val spot = pickupSpots[0]
            val away = Vec3(spot.x, 65.0, spot.z + 8.0)

            // Runner takes a piece (glows, wears it), then the guard shoots them down: the piece lies where they fell.
            guard.holds("pistol")
            hold(runner, spot)
            assertThat(sim.objectiveStage.carrying(runner.id)).`as`("carrier wears a mini-TNT").isNotNull()
            guard.moveTo(away)
            repeat(400) {
                if (eventsOf<com.astarworks.astera.combat.domain.event.PlayerKilled>().any { it.victim == runner.id }) return@repeat
                if (ammo(guard) == 0) guard.drop(sneaking = true)
                guard.faces(runner)
                guard.rightClick()
                advance(10)
            }
            assertThat(eventsOf<com.astarworks.astera.combat.domain.event.PlayerKilled>().any { it.victim == runner.id }).isTrue()
            assertThat(sim.objectiveStage.carrying(runner.id)).`as`("dead carrier is stripped").isNull()
            assertThat(sim.objectiveStage.markers(matchId).keys.filter { it.startsWith("pile-") }).`as`("the piece is marked on the ground").hasSize(1)

            // The guard picks the pile up and plants it in the red base.
            hold(guard, spot)
            assertThat(sim.objectiveStage.carrying(guard.id)).`as`("pile picked up").isNotNull()
            assertThat(sim.objectiveStage.markers(matchId).keys.filter { it.startsWith("pile-") }).isEmpty()
            hold(guard, redBase)
            assertThat(sim.objectiveStage.carrying(guard.id)).`as`("planted").isNull()
            assertThat(eventsOf<MatchEnded>()).isEmpty()
        }
    }
}
