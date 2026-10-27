package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.TeamId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ITEM-26: capture-wool is routed to the imported world (ADR-0281, content/matchmaking/queue.yaml).
 * Their win conditions are played on exactly those stages, so a missing region (wool-*) would fail here.
 */
class ImportedWorldLanesScenarioTest {

    @Test
    fun `capture-wool on temple-trouble - three of the four control points win`() {
        scenario("lane-capture-wool-temple-trouble") {
            val holder = player("holder", at = Vec3(0.5, 65.0, -61.5))
            val rival = player("rival", at = Vec3(0.5, 65.0, 62.5))
            val matchId = startMatch("capture-wool", mapOf(holder to "red", rival to "blue"), stage = "temple-trouble")
            val zones = listOf(Vec3(-23.0, 65.0, 0.0), Vec3(23.0, 65.0, 0.0), Vec3(0.0, 65.0, -6.0), Vec3(-3.0, 65.0, 6.0))
            holder.moveTo(zones[0])
            assertThat(advanceUntil(20 * 12) { blockAt(Vec3(-23.0, 59.0, 0.0)) == "minecraft:red_wool" }).`as`("wool-a captured").isTrue()
            assertThat(eventsOf<MatchEnded>()).isEmpty()
            holder.moveTo(zones[1])
            assertThat(advanceUntil(20 * 12) { blockAt(Vec3(23.0, 59.0, 0.0)) == "minecraft:red_wool" }).`as`("wool-b captured").isTrue()
            assertThat(eventsOf<MatchEnded>()).`as`("2 of 4 is not a majority").isEmpty()
            holder.moveTo(zones[2])
            assertThat(advanceUntil(20 * 12) { eventsOf<MatchEnded>().isNotEmpty() }).isTrue()
            val end = eventsOf<MatchEnded>().single()
            assertThat(end.matchId).isEqualTo(matchId)
            assertThat(end.winningTeam).isEqualTo(TeamId("red"))
        }
    }
}
