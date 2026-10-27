package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandTreeRunner
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression: the Norma picker opens right after MatchStarted, i.e. in the COUNTDOWN phase, but `choose` only accepted
 * ACTIVE / SUDDEN_DEATH and told the player "Norma levels exist only in PvP (not PvE)". Each refusal now carries its own
 * accurate error: lobby, PvE wave (no Norma layer), and a match that is not running.
 */
class NormaChooseGateScenarioTest {

    private fun GameSim.failureKey(name: String, line: String): String? {
        val run = command(name, line)
        return ((run.outcome as? CommandTreeRunner.Outcome.Executed)?.result as? CommandResult.Failure)?.messageKey?.value
    }

    @Test
    fun `choose works in a PvP match during the countdown and when live, and is refused with accurate errors elsewhere`() {
        scenario("norma-choose-gate") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val lobby = player("lobby-1")
            val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()

            assertThat(sim.failureKey("lobby-1", "norma choose kill")).`as`("lobby").isEqualTo("astera.match.norma-choice.not-in-match")

            // Production flow: the match is started and stays in COUNTDOWN while the picker opens.
            val matchId = (lifecycle.start(StageId("siege-isles"), "carry-tnt") as Result.Success).value
            lifecycle.entry(matchId, red.id, TeamId("red"))
            lifecycle.entry(matchId, blue.id, TeamId("blue"))
            advance(60)
            assertThat(sim.services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IMatchRepository>().findById(matchId)!!.phase)
                .isEqualTo(MatchPhase.COUNTDOWN)
            assertThat(sim.command("red-1", "norma choose kill").succeeded).`as`("countdown").isTrue()

            lifecycle.switchPhase(matchId, com.astarworks.astera.match.domain.match.MatchStateMachine.TransitionTrigger.CountdownComplete)
            advance(2)
            assertThat(sim.command("red-1", "norma choose star").succeeded).`as`("live").isTrue()
        }
    }

    @Test
    fun `choose in a PvE wave says the mode has no Norma`() {
        scenario("norma-choose-pve") {
            val solo = player("solo-1", team = "red")
            val matchId = startMatch("pve-waves", mapOf(solo to "red"), stage = "pve-proving-grounds")
            assertThat(matchId).isNotNull
            assertThat(sim.failureKey("solo-1", "norma choose kill")).isEqualTo("astera.match.norma-choice.no-norma-mode")
        }
    }
}
