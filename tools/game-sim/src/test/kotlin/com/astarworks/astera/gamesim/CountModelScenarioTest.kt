package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.match.TeamId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Splatoon-style count model of the siege modes, played on the real `carry-tnt` YAML (`count_start` 100, `count_per_delivery`
 * 34, `time_limit_seconds` 900, `overtime_max_seconds` 60): each delivery takes 34 off the scorer's count, 0 is an
 * immediate knockout win, at the time limit the lower count wins, and a trailing team that still carries the TNT gets
 * overtime until the carry ends (or 60 s pass). Stage-local coordinates of `siege-isles-tnt`.
 */
class CountModelScenarioTest {

    private val tntSpawn = Vec3(0.0, 65.0, -6.0)
    private val redBase = Vec3(-43.0, 65.0, 0.0)
    private val blueBase = Vec3(43.0, 65.0, 0.0)
    private val nowhere = Vec3(0.0, 65.0, -50.0)

    private fun HudLayout.Sidebar.texts(): List<HudComponent.Text> = lines.map { it as HudComponent.Text }

    private fun ScenarioScope.hold(who: SimEntity, at: Vec3, ticks: Int = 40) {
        who.moveTo(at)
        advance(ticks)
    }

    /** [who] takes the TNT from the spawn and delivers it to [base]. */
    private fun ScenarioScope.deliver(who: SimEntity, base: Vec3) {
        hold(who, tntSpawn)
        hold(who, base)
        who.moveTo(nowhere)
    }

    private fun ScenarioScope.counts(viewer: SimEntity): Map<String?, String?> =
        checkNotNull(sim.hud.sidebar(viewer.id)).texts()
            .filter { it.messageKey.value == "astera.hud.sidebar.count" }
            .associate { it.placeholders["team"] to it.placeholders["count"] }

    @Test
    fun `knockout - every delivery counts the sidebar down and the third one wins at once`() {
        scenario("count-knockout") {
            val red = player("red-1", at = nowhere)
            val blue = player("blue-1", at = nowhere)
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"), stage = "siege-isles-tnt")
            advance(40)
            assertThat(counts(red)).containsEntry("red", "100").containsEntry("blue", "100")

            deliver(red, blueBase)
            advance(40)
            assertThat(counts(red)).`as`("one delivery: -34 for the scorer only").containsEntry("red", "66").containsEntry("blue", "100")
            deliver(red, blueBase)
            advance(40)
            assertThat(counts(blue)).containsEntry("red", "32").containsEntry("blue", "100")
            assertThat(eventsOf<MatchEnded>()).`as`("32 left: no knockout yet").isEmpty()

            deliver(red, blueBase)
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
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"), stage = "siege-isles-tnt")

            deliver(red, blueBase) // red 66 first
            deliver(blue, redBase)
            deliver(blue, redBase) // blue 32
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            advance(18_100)
            val end = eventsOf<MatchEnded>().single { it.matchId == matchId }
            assertThat(end.reason).isEqualTo(EndReason.NormaTimeLimit(TeamId("blue")))
            assertThat(end.winningTeam).isEqualTo(TeamId("blue"))
        }
    }

    @Test
    fun `overtime - a trailing carrier extends the match, and delivering ends it`() {
        scenario("count-overtime-delivery") {
            val red = player("red-1", at = nowhere)
            val blue = player("blue-1", at = nowhere)
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"), stage = "siege-isles-tnt")

            deliver(red, blueBase) // red 66, blue 100: blue trails
            advance(17_500)
            hold(blue, tntSpawn) // blue is carrying when the clock runs out
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            advance(500)
            assertThat(eventsOf<MatchEnded>()).`as`("past the limit, but the trailing team holds the objective: overtime").isEmpty()
            assertThat(checkNotNull(sim.hud.sidebar(red.id)).texts().map { it.messageKey.value }).contains("astera.hud.sidebar.phase.count-overtime")

            // The carry ends with a delivery: 66 vs 66, the overtime is over and the draw falls to stars / kills.
            hold(blue, redBase)
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
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"), stage = "siege-isles-tnt")

            deliver(red, blueBase)
            advance(17_500)
            hold(blue, tntSpawn)
            advance(500)
            assertThat(eventsOf<MatchEnded>()).`as`("overtime running").isEmpty()

            advance(20 * 60)
            val end = eventsOf<MatchEnded>().single { it.matchId == matchId }
            assertThat(end.reason).`as`("the 60 s cap ends it; red still leads").isEqualTo(EndReason.NormaTimeLimit(TeamId("red")))
        }
    }
}
