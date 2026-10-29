package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A player carried over at 0 HP from an earlier match must start the next match at full HP and take damage (not look invincible). */
class StaleZeroHpNextMatchScenarioTest {

    @Test
    fun `a player left at 0 HP by a previous match is full HP and damageable in the next match`() {
        scenario("stale-zero-hp-next-match") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow0 = player("yellow-0", team = "yellow") // the annihilation lane starts only with 3 teams
            val first = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow0 to "yellow"), stage = "annihilation-arena")
            sim.services.getOrThrow<IMatchLifecycle>().end(first, EndReason.AdminEnded)
            advance(5)
            sim.services.getOrThrow<IPlayerHealthGateway>().setHealth(blue.id, 0.0, HealthChangeSource.Other("test-stale"))

            startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow0 to "yellow"), stage = "annihilation-arena")
            assertThat(hp(blue)).`as`("HP reset at match start").isGreaterThan(0.0)

            red.moveTo(blue.position + Vec3(0.0, 0.0, 6.0))
            red.holds("pistol")
            red.faces(blue)
            repeat(10) {
                if (eventsOf<DamageDealt>().isEmpty()) {
                    red.rightClick()
                    advance(10)
                }
            }
            assertThat(eventsOf<DamageDealt>()).`as`("blue takes damage in the second match").isNotEmpty()
        }
    }
}
