package com.astarworks.astera.stats.scenario

import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.weapons.domain.weapon.WeaponId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Wave _infra-1 canonical scenario #4.
 *
 * 2v2: kill both blue players, then admin-end the match. Asserts a
 * [MatchEnded] event is published. The Phase 1 VictoryConditionEvaluator
 * decides the winner by score, so we assert "match ended" rather than
 * "red won" — Wave 5e/5f wire team-wipe → automatic MatchEnded with
 * `winningTeam` set; until then this scenario exercises the manual end-on-wipe
 * pattern the smoke test uses.
 *
 * TODO(infra-2): once the team-wipe detector lands, switch the assertion
 * from "any MatchEnded" to "MatchEnded(winningTeam = red)".
 */
class MatchEndsOnTeamWipeScenarioTest {

    @Test
    fun `team wipe followed by admin end publishes MatchEnded`() {
        val sword = WeaponId("amethyst-sword")
        val ctx = scenario {
            val red = TeamId("red")
            val blue = TeamId("blue")
            val r1 = player("red-1", team = red)
            val r2 = player("red-2", team = red)
            val b1 = player("blue-1", team = blue)
            val b2 = player("blue-2", team = blue)

            val matchId = harness.startMatch(StageId("wipe-arena"), listOf(r1, b1, r2, b2))

            // Red wipes blue
            kills(r1, b1, with = sword)
            kills(r2, b2, with = sword)
            shouldBeDead(b1)
            shouldBeDead(b2)

            val result = harness.endMatch(matchId)
            assertThat(result).isNotNull()
        }

        val ends = ctx.published<MatchEnded>()
        assertThat(ends)
            .withFailMessage { "expected at least one MatchEnded; saw ${ctx.harness.publishedEvents.size} total events" }
            .isNotEmpty()
        assertThat(ends.last().reason).isEqualTo(EndReason.AdminEnded)
    }
}
