package com.astarworks.astera.gamesim.playable

import com.astarworks.astera.gamesim.GameSim
import com.astarworks.astera.gamesim.playable.agent.AgentPort
import com.astarworks.astera.gamesim.playable.agent.DriverMatchHost
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.StringReader

/** ADR-0323: the sim's team count comes from the stage, never from a literal; a count the stage has no layout for fails loudly. */
class TeamCountScenarioTest {

    @Test
    fun `default match seats exactly the shipped stage's own teams`() {
        val stageTeams = StageTeams.of("annihilation-arena")
        MatchDriver.boot(MatchConfig(stage = "annihilation-arena", botsPerTeam = 2, seed = 5L)).use { d ->
            assertThat(d.seats.map { it.team }.distinct()).containsExactlyElementsOf(stageTeams)
            assertThat(d.seats).hasSize(stageTeams.size * 2)
        }
    }

    @Test
    fun `a requested team count without a stage team_layouts entry fails naming the count and the stage`() {
        for (n in listOf(2, 4)) {
            assertThatThrownBy { MatchDriver.boot(MatchConfig(stage = "annihilation-arena", teamCount = n, seed = 5L)) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("requested $n teams")
                .hasMessageContaining("annihilation-arena")
                .hasMessageContaining("team_layouts")
        }
    }

    @Test
    fun `an unknown team in an explicit list fails loudly`() {
        assertThatThrownBy { MatchDriver.boot(MatchConfig(stage = "annihilation-arena", teams = listOf("red", "green"), seed = 5L)) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("green")
    }

    @Test
    fun `the AgentPort new op takes a team count and reports the loud error for a count the stage lacks`() {
        val port = AgentPort(DriverMatchHost, GameSim.defaultContentDir().toFile(), GameSim.defaultTraceDir())
        val out = StringBuilder()
        port.run(
            BufferedReader(StringReader("""{"op":"new","mode":"annihilation","stage":"annihilation-arena","teams":4,"bots_per_team":1}""")),
            out,
        )
        assertThat(out.toString()).contains("requested 4 teams").contains("team_layouts")
    }
}
