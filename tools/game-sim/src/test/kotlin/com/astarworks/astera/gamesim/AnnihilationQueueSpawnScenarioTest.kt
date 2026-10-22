package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.stage.SpawnRole
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.domain.mode.GameModeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** WP9: three players queue the annihilation lane -> each team spawns at one of its own spawns. */
class AnnihilationQueueSpawnScenarioTest {

    @Test
    fun `three players queue annihilation and each team spawns at its own spawn`() {
        scenario("annihilation-queue-spawns") {
            val players = listOf(player("p1"), player("p2"), player("p3"))
            val matchmaker = sim.services.getOrThrow<IMatchmaker>()
            val mode = MatchmakingMode.Custom(GameModeId("annihilation"))
            players.forEach { check(matchmaker.enqueue(it.id, mode) is Result.Success) { "enqueue ${it.name}" } }

            val matches = sim.services.getOrThrow<IMatchRepository>()
            assertThat(advanceUntil(600) { eventsOf<MatchStarted>().isNotEmpty() })
                .`as`("queue countdown ends and the match starts").isTrue()
            val started = eventsOf<MatchStarted>().single()
            assertThat(started.modeKey).isEqualTo("annihilation")
            assertThat(started.stageId).isEqualTo(StageId("annihilation-arena"))

            val match = checkNotNull(matches.findById(started.matchId))
            val stage = checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(started.stageId))
            assertThat(match.teams.map { it.id.value }).containsExactly("red", "blue", "yellow")

            val spawnPositionsByTeam = match.teams.associate { team ->
                team.id to stage.spawnPoints
                    .filter { it.role == SpawnRole.TEAM && it.team == team.id }
                    .map { it.position }
            }
            spawnPositionsByTeam.values.forEach { assertThat(it).isNotEmpty() }

            // Every queued player was put on a team and stands on a spawn of that team.
            val landed = mutableSetOf<com.astarworks.astera.core.domain.geometry.Vec3>()
            for (p in players) {
                val team = checkNotNull(match.teams.firstOrNull { p.id in it.members }) { "${p.name} has no team" }
                assertThat(p.position).isIn(spawnPositionsByTeam.getValue(team.id))
                landed += p.position
            }
            assertThat(match.teams.count { it.members.isNotEmpty() }).`as`("one player per team").isEqualTo(3)
            assertThat(landed).hasSize(3)
        }
    }
}
