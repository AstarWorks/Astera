package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.match.domain.match.MatchStateMachine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Scenario 3: a match goes start -> countdown -> active -> ended through the real lifecycle facade. */
class MatchLifecycleScenarioTest {

    @Test
    fun `match starts spawns players and ends`() {
        scenario("match-start-to-end") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")

            val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
            val matches = sim.services.getOrThrow<IMatchRepository>()

            val matchId = when (val started = lifecycle.start(StageId("example-arena"))) {
                is Result.Success -> started.value
                is Result.Failure -> error("start failed: ${started.error}")
            }
            assertThat(matches.findById(matchId)?.phase).isEqualTo(MatchPhase.COUNTDOWN)

            lifecycle.entry(matchId, red.id, TeamId("red"))
            lifecycle.entry(matchId, blue.id, TeamId("blue"))

            lifecycle.switchPhase(matchId, MatchStateMachine.TransitionTrigger.CountdownComplete)
            advance(40)
            assertThat(matches.findById(matchId)?.phase).isEqualTo(MatchPhase.ACTIVE)
            assertThat(eventsOf<MatchStarted>().map { it.matchId }).contains(matchId)

            lifecycle.end(matchId, EndReason.AdminEnded)
            advance(40)
            assertThat(matches.findById(matchId)?.phase).isEqualTo(MatchPhase.ENDED)
            assertThat(eventsOf<MatchEnded>().map { it.matchId }).contains(matchId)
        }
    }
}
