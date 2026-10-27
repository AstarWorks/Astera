package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.stage.StageId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Splatoon-style count model of the siege modes, played on the real `steal-jewelry` YAML (`count_start` 100, `count_per_gem`
 * 20, `time_limit_seconds` 900, `overtime_max_seconds` 60): each banked jewel takes 20 off the scorer's count, 0 is an
 * immediate knockout win, at the time limit the lower count wins, and a trailing team that still carries a jewel gets
 * overtime until the carry ends (or 60 s pass). Regions of `siege-isles-jewelry` are read from the stage registry.
 */
class CountModelScenarioTest {

    private val nowhere = Vec3(0.0, 65.0, -50.0)

    private fun ScenarioScope.spotOf(region: String): Vec3 {
        val r = checkNotNull(checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(StageId("siege-isles-jewelry"))).region(region)) { "no region $region" }
        return Vec3((r.min.x + r.max.x) / 2.0, (r.min.y + 1.0).coerceAtMost(r.max.y), (r.min.z + r.max.z) / 2.0)
    }

    private fun HudLayout.Sidebar.texts(): List<HudComponent.Text> = lines.map { it as HudComponent.Text }

    private fun ScenarioScope.hold(who: SimEntity, at: Vec3, ticks: Int = 40) {
        who.moveTo(at)
        advance(ticks)
    }

    /** [who] of [team] steals a jewel from [enemy]'s pedestal (4 s hold) and banks it at its own base. */
    private fun ScenarioScope.bank(who: SimEntity, team: String, enemy: String) {
        hold(who, spotOf("jewelry-$enemy"), 110)
        hold(who, spotOf("base-$team"), 40)
        who.moveTo(nowhere)
    }

    private fun ScenarioScope.counts(viewer: SimEntity): Map<String?, String?> =
        checkNotNull(sim.hud.sidebar(viewer.id)).texts()
            .filter { it.messageKey.value == "astera.hud.sidebar.count" }
            .associate { it.placeholders["team"] to it.placeholders["count"] }

    @Test
    fun `knockout - every banked jewel counts the sidebar down and the fifth one wins at once`() {
        scenario("count-knockout") {
            val red = player("red-1", at = nowhere)
            val blue = player("blue-1", at = nowhere)
            val matchId = startMatch("steal-jewelry", mapOf(red to "red", blue to "blue"), stage = "siege-isles-jewelry")
            advance(40)
            assertThat(counts(red)).containsEntry("red", "100").containsEntry("blue", "100")

            bank(red, "red", "blue")
            advance(40)
            assertThat(counts(red)).`as`("one jewel: -20 for the scorer only").containsEntry("red", "80").containsEntry("blue", "100")
            repeat(3) { bank(red, "red", "blue") }
            advance(40)
            assertThat(counts(blue)).containsEntry("red", "20").containsEntry("blue", "100")
            assertThat(eventsOf<MatchEnded>()).`as`("20 left: no knockout yet").isEmpty()

            bank(red, "red", "blue")
            val end = eventsOf<MatchEnded>().single()
            assertThat(end.matchId).isEqualTo(matchId)
            assertThat(end.winningTeam).isEqualTo(TeamId("red"))
        }
    }

    @Test
    fun `time out - the lower count wins even when the other team scored first`() {
        scenario("count-timeout-ranking") {
            val red = player("red-1", at = nowhere)
            val blue = player("blue-1", at = nowhere)
            val matchId = startMatch("steal-jewelry", mapOf(red to "red", blue to "blue"), stage = "siege-isles-jewelry")

            bank(red, "red", "blue") // red 80 first
            bank(blue, "blue", "red")
            bank(blue, "blue", "red") // blue 60
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            advance(18_100)
            val end = eventsOf<MatchEnded>().single { it.matchId == matchId }
            assertThat(end.reason).isEqualTo(EndReason.NormaTimeLimit(TeamId("blue")))
            assertThat(end.winningTeam).isEqualTo(TeamId("blue"))
        }
    }

    @Test
    fun `overtime - a trailing carrier extends the match, and banking ends it`() {
        scenario("count-overtime-delivery") {
            val red = player("red-1", at = nowhere)
            val blue = player("blue-1", at = nowhere)
            val matchId = startMatch("steal-jewelry", mapOf(red to "red", blue to "blue"), stage = "siege-isles-jewelry")

            bank(red, "red", "blue") // red 80, blue 100: blue trails
            advance(17_300)
            hold(blue, spotOf("jewelry-red"), 110) // blue is carrying a jewel when the clock runs out
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            advance(500)
            assertThat(eventsOf<MatchEnded>()).`as`("past the limit, but the trailing team holds the objective: overtime").isEmpty()
            assertThat(checkNotNull(sim.hud.sidebar(red.id)).texts().map { it.messageKey.value }).contains("astera.hud.sidebar.phase.count-overtime")

            // The carry ends with a bank: 80 vs 80, the overtime is over and the draw falls to stars / kills.
            hold(blue, spotOf("base-blue"), 40)
            assertThat(advanceUntil(100) { eventsOf<MatchEnded>().isNotEmpty() }).isTrue()
            val end = eventsOf<MatchEnded>().single { it.matchId == matchId }
            assertThat(end.reason).isEqualTo(EndReason.NormaTimeLimit(null))
        }
    }

    @Test
    fun `overtime - is capped at overtime_max_seconds when the carry never ends`() {
        scenario("count-overtime-cap") {
            val red = player("red-1", at = nowhere)
            val blue = player("blue-1", at = nowhere)
            val matchId = startMatch("steal-jewelry", mapOf(red to "red", blue to "blue"), stage = "siege-isles-jewelry")

            bank(red, "red", "blue")
            advance(17_300)
            hold(blue, spotOf("jewelry-red"), 110)
            advance(500)
            assertThat(eventsOf<MatchEnded>()).`as`("overtime running").isEmpty()

            advance(20 * 60)
            val end = eventsOf<MatchEnded>().single { it.matchId == matchId }
            assertThat(end.reason).`as`("the 60 s cap ends it; red still leads").isEqualTo(EndReason.NormaTimeLimit(TeamId("red")))
        }
    }
}
