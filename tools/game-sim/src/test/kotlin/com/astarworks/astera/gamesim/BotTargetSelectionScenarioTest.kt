package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.TargetPolicy
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.domain.event.PlayerRespawned
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A dead player waits out the respawn in spectator mode and the server ignores spectators as targets. The bot's target
 * choice ([TargetPolicy], the object BotBrain calls) must therefore skip them: it fights the living enemy, never the
 * corpse, and picks the victim up again once it has respawned. Driven against the real death / respawn wiring.
 */
class BotTargetSelectionScenarioTest {

    private fun ScenarioScope.candidate(entity: SimEntity, team: String) = TargetPolicy.Candidate(
        id = entity.id.hashCode(), name = entity.name, team = team, spectating = sim.spectator.isSpectating(entity.id),
        x = entity.position.x, y = entity.position.y, z = entity.position.z,
    )

    @Test
    fun `bot never targets an enemy in the death cam and targets it again after the respawn`() {
        scenario("bot-target-skips-dead") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val blue2 = player("blue-2", team = "blue")
            startMatch("annihilation", mapOf(red to "red", blue to "blue", blue2 to "blue"), stage = "annihilation-arena")
            fun pick() = TargetPolicy.nearest(
                red.name, "red", red.position.x, red.position.y, red.position.z,
                listOf(candidate(blue, "blue"), candidate(blue2, "blue")), 70.0,
            )

            sim.setMaxHealth(blue, 1.0)
            blue.moveTo(red.position + Vec3(0.0, 0.0, 6.0))
            blue2.moveTo(red.position + Vec3(0.0, 0.0, 30.0))
            red.holds("pistol")
            red.faces(blue)
            assertThat(pick()?.name).`as`("alive and nearest").isEqualTo(blue.name)

            repeat(20) {
                if (eventsOf<PlayerKilled>().none { it.victim == blue.id }) {
                    red.rightClick()
                    advance(10)
                }
            }
            assertThat(eventsOf<PlayerKilled>().any { it.victim == blue.id }).isTrue()
            assertThat(sim.spectator.isSpectating(blue.id)).isTrue()

            // The corpse is 6 blocks away and nearer than blue-2; the bot must still go for blue-2.
            assertThat(pick()?.name).`as`("the dead enemy in the death cam is not a target").isEqualTo(blue2.name)

            advance(110)
            assertThat(eventsOf<PlayerRespawned>().any { it.playerId == blue.id }).isTrue()
            assertThat(sim.spectator.isSpectating(blue.id)).isFalse()
            assertThat(pick()?.name).`as`("a respawned enemy is a target again").isNotNull()
        }
    }
}
