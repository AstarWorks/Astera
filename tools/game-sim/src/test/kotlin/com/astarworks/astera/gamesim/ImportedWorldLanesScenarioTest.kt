package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.match.application.gamemode.siege.ObjectiveKit
import kotlin.math.floor
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
            val zones = matchStage(matchId).regionsWithPrefix("wool-").sortedBy { it.name }
            assertThat(zones).hasSize(2)
            val markers = zones.map { ObjectiveKit.centreOf(it).let { p -> Vec3(floor(p.x), p.y, floor(p.z)) } }
            holder.moveTo(ObjectiveKit.centreOf(zones[0], 1.0))
            assertThat(advanceUntil(20 * 12) { blockAt(markers[0]) == "minecraft:red_wool" }).`as`("wool-c captured").isTrue()
            assertThat(eventsOf<MatchEnded>()).`as`("1 of 2 is not a majority").isEmpty()
            holder.moveTo(ObjectiveKit.centreOf(zones[1], 1.0))
            assertThat(advanceUntil(20 * 12) { blockAt(markers[1]) == "minecraft:red_wool" }).`as`("wool-d captured").isTrue()
            assertThat(eventsOf<MatchEnded>()).`as`("owning both zones starts the countdown, rather than winning immediately").isEmpty()
            advance(20 * 100) // The shipped count model counts 100 down at one per second.
            assertThat(advanceUntil(20 * 12) { eventsOf<MatchEnded>().isNotEmpty() }).isTrue()
            val end = eventsOf<MatchEnded>().single()
            assertThat(end.matchId).isEqualTo(matchId)
            assertThat(end.winningTeam).isEqualTo(TeamId("red"))
        }
    }
}
