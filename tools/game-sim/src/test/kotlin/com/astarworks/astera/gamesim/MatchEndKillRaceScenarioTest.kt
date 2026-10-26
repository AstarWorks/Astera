package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.PlayerRespawned
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The last victim: a kill that lands on the very tick the match ends must not strand the victim dead or leave a respawn running. */
class MatchEndKillRaceScenarioTest {

    @Test
    fun `a kill and the match end in the same tick leave the victim alive and out of the death countdown`() {
        scenario("end-kill-race") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue"), stage = "annihilation-arena")
            sim.setMaxHealth(blue, 1.0)
            red.moveTo(blue.position + Vec3(0.0, 0.0, 6.0))
            red.holds("pistol")
            red.faces(blue)

            // Fire, then end the match in the same tick the shot resolves.
            red.rightClick()
            sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.AdminEnded)
            advance(5)
            assertThat(eventsOf<MatchEnded>()).hasSize(1)

            // Let any respawn countdown run out: nobody may be left spectating or waiting.
            advance(140)
            assertThat(sim.spectator.isSpectating(blue.id)).`as`("the victim is not stuck in the death cam after the match").isFalse()
            assertThat(sim.spectator.isSpectating(red.id)).isFalse()
            if (eventsOf<PlayerKilled>().any { it.victim == blue.id }) {
                assertThat(eventsOf<PlayerRespawned>().count { it.playerId == blue.id }).`as`("a kill at match end never produces a match respawn").isZero()
            }
            assertThat(hp(blue)).`as`("the victim is alive again after the match").isGreaterThan(0.0)
        }
    }
}
