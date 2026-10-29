package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IEgoGauge
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EgoGaugeChanged
import com.astarworks.astera.core.domain.event.ResourceReloaded
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0231: the E.G.O gauge in the cast path, the HUD readout, and Shift+Q reload next to the Q slot. */
class EgoGaugeScenarioTest {

    @Test
    fun `sneak drop still reloads a gun and a plain drop does not`() {
        scenario("ego-sneak-drop-reload") {
            val shooter = player("shooter", team = "red")
            val dummy = mob("dummy", at = Vec3(0.0, groundY, 12.0), team = "blue")
            sim.setMaxHealth(dummy, 500.0)
            // The pistol reloads on a plain Q (499e39513, tutorial); the rifle keeps Shift+Q so Q stays the E.G.O slot.
            shooter.holds("rifle")
            shooter.faces(dummy)

            repeat(3) {
                shooter.rightClick()
                advance(30)
            }
            assertThat(ammo(shooter)).isEqualTo(17)

            // Q alone is the E.G.O slot: the rifle binds none, so nothing happens.
            assertThat(sim.drop(shooter)).isFalse()
            advance(40)
            assertThat(ammo(shooter)).isEqualTo(17)

            // Shift+Q is the reload.
            assertThat(sim.drop(shooter, sneaking = true)).isTrue()
            advance(40)
            assertThat(ammo(shooter)).isEqualTo(20)
            assertThat(eventsOf<ResourceReloaded>()).hasSize(1)
        }
    }

    @Test
    fun `death empties the gauge`() {
        scenario("ego-death-reset") {
            val gauge = sim.services.getOrThrow<IEgoGauge>()
            val shooter = player("shooter", team = "red")
            val victim = player("victim", at = Vec3(0.0, groundY, 10.0), team = "blue")
            shooter.holds("pistol")
            shooter.faces(victim)

            repeat(20) {
                if (eventsOf<com.astarworks.astera.combat.domain.event.PlayerKilled>().isEmpty()) {
                    shooter.rightClick()
                    advance(10)
                }
            }

            assertThat(eventsOf<com.astarworks.astera.combat.domain.event.PlayerKilled>()).hasSize(1)
            assertThat(gauge.value(victim.id)).isZero()
            assertThat(gauge.value(shooter.id)).isGreaterThan(0)
        }
    }
}
