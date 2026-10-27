package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.domain.event.StarsCollected
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * "Stars cannot be picked up": a player left in spectator mode by an earlier match (eliminated team, disconnect while
 * waiting to respawn; the game mode is saved with the player) is not a pickup candidate in the next match, so every
 * star touch is silently ignored. Entering a match must always start the player as a normal participant.
 */
class StarsAfterSpectatorLeakScenarioTest {

    @Test
    fun `a player left spectating by the previous match picks stars up in the next one`() {
        scenario("stars-after-spectator-leak") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val first = startMatch("battle-loyal", mapOf(red to "red", blue to "blue"), stage = "example-arena")
            sim.services.getOrThrow<IMatchLifecycle>().end(first, EndReason.AdminEnded)
            advance(5)
            sim.spectator.startSpectating(red.id)
            assertThat(sim.spectator.isSpectating(red.id)).isTrue()

            val matchId = startMatch("battle-loyal", mapOf(red to "red", blue to "blue"), stage = "example-arena")
            assertThat(sim.spectator.isSpectating(red.id)).`as`("entering a match ends spectator mode").isFalse()

            val generators = sim.services.getOrThrow<IGeneratorRepository>().listForMatch(matchId)
            red.moveTo(generators.maxBy { it.position.x }.position)
            blue.moveTo(generators.minBy { it.position.x }.position + com.astarworks.astera.core.domain.geometry.Vec3(-40.0, 0.0, 0.0))
            advance(450)
            assertThat(eventsOf<StarsCollected>().filter { it.playerId == red.id }).`as`("red collected a pop").isNotEmpty()
            assertThat(sim.services.getOrThrow<IMatchStarStore>().get(matchId, red.id)).isGreaterThanOrEqualTo(1)
        }
    }
}
