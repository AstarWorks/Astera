package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.event.CastRejectionReason
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.event.SkillCastRejected
import com.astarworks.astera.core.application.port.outbound.IEgoGauge
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EgoGaugeChanged
import com.astarworks.astera.core.domain.event.ResourceReloaded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skill.SkillId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0231: the E.G.O gauge in the cast path, the HUD readout, and Shift+Q reload next to the Q slot. */
class EgoGaugeScenarioTest {

    @Test
    fun `hits fill the gauge, a skill with ego_cost is refused short and consumes it when full`() {
        scenario("ego-gauge") {
            val gauge = sim.services.getOrThrow<IEgoGauge>()
            val shooter = player("shooter", team = "red")
            val dummy = player("dummy", at = Vec3(0.0, groundY, 12.0), team = "blue")
            sim.setMaxHealth(dummy, 10_000.0)
            shooter.holds("pistol")
            shooter.faces(dummy)

            // A throwaway E.G.O skill: the pistol shot with an E.G.O price tag and no other cost.
            val base = checkNotNull(sim.combat.skillContents.find(SkillId("pistol-shot")))
            sim.combat.skillContents.register(
                base.copy(id = SkillId("ego-test"), egoCost = gauge.config.max, resourceCost = 0, cooldownTicks = 0),
            )

            // Gain on hit: the shooter fills by gain_on_dealt per landed shot, the target by gain_on_taken.
            shooter.rightClick()
            advance(30)
            assertThat(gauge.value(shooter.id)).isGreaterThanOrEqualTo(gauge.config.gainOnDealt)
            assertThat(gauge.value(dummy.id)).isGreaterThanOrEqualTo(gauge.config.gainOnTaken)

            // Short gauge: refused with the E.G.O reason, nothing spent.
            val before = gauge.value(shooter.id)
            shooter.cast("ego-test")
            advance(1)
            assertThat(eventsOf<SkillCastRejected>().map { it.reason }).contains(CastRejectionReason.INSUFFICIENT_EGO)
            assertThat(eventsOf<SkillCast>().count { it.skillId == SkillId("ego-test") }).isZero()
            assertThat(gauge.value(shooter.id)).isGreaterThanOrEqualTo(before)

            // Fill it: READY edge fires once, the readout shows READY, the cast goes through and empties it.
            gauge.gain(shooter.id, gauge.config.max)
            assertThat(eventsOf<EgoGaugeChanged>().count { it.playerId == shooter.id && it.becameFull }).isEqualTo(1)
            advance(12)
            assertThat(sim.hud.actionBar(shooter.id)).isNotNull
            shooter.cast("ego-test")
            advance(1)
            assertThat(eventsOf<SkillCast>().count { it.skillId == SkillId("ego-test") }).isEqualTo(1)
            assertThat(gauge.value(shooter.id)).isZero()
        }
    }

    @Test
    fun `sneak drop still reloads a gun and a plain drop does not`() {
        scenario("ego-sneak-drop-reload") {
            val shooter = player("shooter", team = "red")
            val dummy = mob("dummy", at = Vec3(0.0, groundY, 12.0), team = "blue")
            sim.setMaxHealth(dummy, 500.0)
            shooter.holds("pistol")
            shooter.faces(dummy)

            repeat(3) {
                shooter.rightClick()
                advance(6)
            }
            assertThat(ammo(shooter)).isEqualTo(9)

            // Q alone is the E.G.O slot: the pistol binds none, so nothing happens.
            assertThat(sim.drop(shooter)).isFalse()
            advance(40)
            assertThat(ammo(shooter)).isEqualTo(9)

            // Shift+Q is the reload.
            assertThat(sim.drop(shooter, sneaking = true)).isTrue()
            advance(40)
            assertThat(ammo(shooter)).isEqualTo(12)
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
