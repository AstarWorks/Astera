package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.service.arena.VoidFallKiller
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.PlayerRespawned
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Falling below the arena kill plane is a normal match death: credited to a recent attacker, else environmental, then respawn. */
class VoidFallScenarioTest {

    @Test
    fun `a void fall kills, credits the last attacker within 10s, and respawns the victim`() {
        scenario("void-fall") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val green = player("red-2", team = "red")
            startMatch("annihilation", mapOf(red to "red", blue to "blue", green to "red"), stage = "annihilation-arena")
            val killer = VoidFallKiller(
                health = { sim.services.getOrThrow<IPlayerHealthGateway>() },
                currentTick = { sim.tick },
            )

            // Blue is shot once (survives), then walks off the island: the fall is red's kill.
            sim.setMaxHealth(blue, 1000.0)
            red.moveTo(blue.position + Vec3(0.0, 0.0, 6.0))
            red.holds("pistol")
            red.faces(blue)
            red.rightClick()
            advance(20)
            assertThat(hp(blue)).isLessThan(1000.0)
            advance(60)
            assertThat(killer.kill(blue.id)).isTrue()
            advance(2)
            val kill = eventsOf<PlayerKilled>().first { it.victim == blue.id }
            assertThat(kill.killer).`as`("credited to the last attacker").isEqualTo(red.id)

            advance(110)
            assertThat(eventsOf<PlayerRespawned>().any { it.playerId == blue.id }).`as`("normal respawn flow").isTrue()

            // Nobody hit green: a self / void death without a killer.
            assertThat(killer.kill(green.id)).isTrue()
            advance(2)
            val selfKill = eventsOf<PlayerKilled>().first { it.victim == green.id }
            assertThat(selfKill.killer).isNull()
            // A second plane crossing during the same fall does not kill twice.
            assertThat(killer.kill(green.id)).isFalse()
        }
    }
}
