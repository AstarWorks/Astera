package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IEgoGauge
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** content/balance/health-regen.yaml: +1 HP/s after 5 s without a hit dealt or taken; any hit pauses it again. */
class HealthRegenScenarioTest {

    @Test
    fun `hp regens slowly after the delay and a hit pauses it`() {
        scenario("health-regen") {
            val victim = player("victim", team = "blue")
            val health = sim.services.getOrThrow<IPlayerHealthGateway>()
            val ego = sim.services.getOrThrow<IEgoGauge>()

            // A hit as the combat listener books it: HP down, combat clock (the ego gauge's last gain) moves.
            fun takeHit() {
                health.damage(victim.id, 10.0, HealthChangeSource.Other("test"))
                ego.gain(victim.id, ego.config.gainOnTaken)
            }

            takeHit()
            val hurt = hp(victim)
            assertThat(hurt).isLessThan(100.0)

            // Inside the 5 s out-of-combat window: no regen.
            advance(60)
            assertThat(hp(victim)).isEqualTo(hurt)

            // Past it: +1 HP per second.
            advance(120)
            val regenned = hp(victim)
            assertThat(regenned).isGreaterThan(hurt)
            assertThat(regenned - hurt).isBetween(1.0, 6.0)

            // Another hit pauses regen again.
            takeHit()
            val hurtAgain = hp(victim)
            advance(60)
            assertThat(hp(victim)).isEqualTo(hurtAgain)

            // And it resumes once the delay has elapsed again, never past max HP.
            advance(200)
            assertThat(hp(victim)).isGreaterThan(hurtAgain)
            advance(20 * 200)
            assertThat(hp(victim)).isEqualTo(100.0)
        }
    }
}
