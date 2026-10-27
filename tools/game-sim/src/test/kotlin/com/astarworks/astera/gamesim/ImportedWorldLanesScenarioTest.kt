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
    fun `capture-wool on temple-trouble - holding both middle control points wins`() {
        scenario("lane-capture-wool-temple-trouble") {
            val holder = player("holder", at = Vec3(0.5, 65.0, -75.5))
            val rival = player("rival", at = Vec3(0.5, 65.0, 76.5))
            val matchId = startMatch("capture-wool", mapOf(holder to "red", rival to "blue"), stage = "temple-trouble")
            val zones = listOf(Vec3(0.0, 65.0, -6.0), Vec3(0.0, 65.0, 6.0))
            holder.moveTo(zones[0])
            assertThat(advanceUntil(20 * 12) { blockAt(Vec3(0.0, 59.0, -6.0)) == "minecraft:red_wool" }).`as`("wool-c captured").isTrue()
            assertThat(eventsOf<MatchEnded>()).`as`("1 of 2 is not a majority").isEmpty()
            holder.moveTo(zones[1])
            assertThat(advanceUntil(20 * 12) { eventsOf<MatchEnded>().isNotEmpty() }).isTrue()
            val end = eventsOf<MatchEnded>().single()
            assertThat(end.matchId).isEqualTo(matchId)
            assertThat(end.winningTeam).isEqualTo(TeamId("red"))
        }
    }
}
