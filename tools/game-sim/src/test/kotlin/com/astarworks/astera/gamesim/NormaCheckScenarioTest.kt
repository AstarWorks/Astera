package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandTreeRunner
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.match.application.port.outbound.IMatchNormaStore
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.domain.event.NormaCheckReady
import com.astarworks.astera.match.domain.event.NormaLevelUp
import com.astarworks.astera.match.domain.event.StarsCollected
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The Norma check (founder 2026-10-29): meeting the level goal only makes the player READY; the level rises when the
 * player runs `/astera norma check` inside their OWN team's base while still meeting the goal. Played on the real
 * `content/` (level 1 -> 2 needs 10 stars).
 */
class NormaCheckScenarioTest {

    private fun GameSim.checkResult(name: String): CommandResult? =
        (command(name, "norma check").outcome as? CommandTreeRunner.Outcome.Executed)?.result

    private fun GameSim.failureKey(name: String): String? = (checkResult(name) as? CommandResult.Failure)?.messageKey?.value

    @Test
    fun `goal met only readies the player, the check at the own base levels up, enemy base and an unmet goal are refused`() {
        scenario("norma-check") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"))
            val stars = sim.services.getOrThrow<IMatchStarStore>()
            val norma = sim.services.getOrThrow<IMatchNormaStore>()
            walkToBase(matchId, red, TeamId("red"))

            // Goal met: no level yet, a ready notice goes out once.
            stars.add(matchId, red.id, 10)
            sim.eventBus.publish(StarsCollected(matchId, red.id, 10, stars.get(matchId, red.id)))
            advance(2)
            assertThat(norma.get(matchId, red.id).level).`as`("goal met, no check yet").isEqualTo(1)
            assertThat(eventsOf<NormaLevelUp>().filter { it.playerId == red.id }).isEmpty()
            assertThat(eventsOf<NormaCheckReady>().filter { it.playerId == red.id }).hasSize(1)

            // Enemy base: refused.
            walkToBase(matchId, red, TeamId("blue"))
            assertThat(sim.failureKey("red-1")).isEqualTo("astera.match.norma-check.enemy-base")
            // Neither base (the stage centre / far away): refused.
            red.moveTo(baseCentre(matchId, TeamId("red")).copy(y = 200.0))
            advance(2)
            assertThat(sim.failureKey("red-1")).isEqualTo("astera.match.norma-check.not-at-base")
            assertThat(norma.get(matchId, red.id).level).isEqualTo(1)

            // Own base, but the stars were lost (died): no longer ready, the shortfall is reported.
            walkToBase(matchId, red, TeamId("red"))
            stars.spend(matchId, red.id, 4)
            val run = sim.command("red-1", "norma check")
            val failure = (run.outcome as CommandTreeRunner.Outcome.Executed).result as CommandResult.Failure
            assertThat(failure.messageKey.value).isEqualTo("astera.match.norma-check.not-ready-star")
            assertThat(failure.placeholders["missing"]).isEqualTo("4")
            assertThat(norma.get(matchId, red.id).level).isEqualTo(1)

            // Back above the goal, the notice fires again; the check at the own base now levels up (stars are kept).
            stars.add(matchId, red.id, 4)
            sim.eventBus.publish(StarsCollected(matchId, red.id, 4, stars.get(matchId, red.id)))
            advance(2)
            assertThat(eventsOf<NormaCheckReady>().filter { it.playerId == red.id }).hasSize(2)
            assertThat(sim.command("red-1", "norma check").succeeded).isTrue()
            advance(2)
            assertThat(norma.get(matchId, red.id).level).isEqualTo(2)
            assertThat(eventsOf<NormaLevelUp>().filter { it.playerId == red.id }.map { it.level }).containsExactly(2)
            assertThat(stars.get(matchId, red.id)).`as`("stars are not consumed").isEqualTo(10)
        }
    }
}
