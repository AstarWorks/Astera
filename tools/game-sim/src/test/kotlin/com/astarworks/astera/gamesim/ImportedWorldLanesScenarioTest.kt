package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.TeamId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ITEM-26: break-core and capture-wool are routed to the imported worlds (ADR-0281, content/matchmaking/queue.yaml).
 * Their win conditions are played on exactly those stages, so a missing region (core-<team>, wool-*) would fail here.
 */
class ImportedWorldLanesScenarioTest {

    @Test
    fun `break-core on medieval-village - the enemy core on the plinth wins, your own is put back`() {
        scenario("lane-break-core-medieval-village") {
            val attacker = player("attacker", at = Vec3(-8.5, 65.0, 71.5))
            val defender = player("defender", at = Vec3(-8.5, 65.0, -36.5))
            val matchId = startMatch("break-core", mapOf(attacker to "red", defender to "blue"), stage = "medieval-village")
            val redCore = Vec3(18.0, 65.0, 74.0)
            val blueCore = Vec3(18.0, 65.0, -40.0)
            assertThat(blockAt(redCore)).isEqualTo("minecraft:emerald_block")
            assertThat(blockAt(blueCore)).isEqualTo("minecraft:emerald_block")

            attacker.breaks(redCore)
            assertThat(blockAt(redCore)).`as`("own core comes back").isEqualTo("minecraft:emerald_block")
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            attacker.breaks(blueCore)
            val end = eventsOf<MatchEnded>().single()
            assertThat(end.matchId).isEqualTo(matchId)
            assertThat(end.winningTeam).isEqualTo(TeamId("red"))
        }
    }

    @Test
    fun `capture-wool on temple-trouble - three of the four control points win`() {
        scenario("lane-capture-wool-temple-trouble") {
            val holder = player("holder", at = Vec3(0.5, 65.0, -82.5))
            val rival = player("rival", at = Vec3(0.5, 65.0, 83.5))
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
