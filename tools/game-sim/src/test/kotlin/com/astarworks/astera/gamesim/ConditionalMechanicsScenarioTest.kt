package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * SKL-10: conditional mechanics of the v2 grammar on the shipped content, run by the real cast runner.
 * `window` (parry of couture-rapier-def) and `when: nearest_enemy_dist(...)` (charge of bastion-greatsword-s1).
 */
class ConditionalMechanicsScenarioTest {

    private val attacker = simVictim(1)
    private val farAttacker = simVictim(2)

    private fun parrySim() = CastSim(
        victims = mapOf(attacker to Vec3(0.0, 0.0, 2.0), farAttacker to Vec3(0.0, 0.0, 12.0)),
    )

    @Test
    fun `parry answers a close hit inside the window with the v1 riposte and leaves the hit alone`() {
        val sim = parrySim()
        sim.start("couture-rapier-def")
        sim.step(5)
        assertThat(sim.incoming(attacker, 12.0)).describedAs("v1 counter does not negate the hit").isEqualTo(1.0)
        sim.step(30)
        val riposte = sim.dealt.filter { it.victim == attacker }
        assertThat(riposte).describedAs("riposte lands on the attacker").hasSize(1)
        assertThat(riposte[0].amount).isEqualTo(30.0)
        assertThat(riposte[0].tick).describedAs("riposte is delayed ~7 ticks after the parry").isBetween(11, 14)
        assertThat(sim.kit.runner.diagnostics()).isEmpty()
    }

    @Test
    fun `parry is one shot and ignores hits outside the filter range`() {
        val sim = parrySim()
        sim.start("couture-rapier-def")
        sim.step(5)
        assertThat(sim.incoming(farAttacker, 12.0)).describedAs("attacker beyond 3.5 blocks is not parried").isEqualTo(1.0)
        sim.incoming(attacker, 12.0)
        sim.incoming(attacker, 12.0)
        sim.step(35)
        assertThat(sim.dealt.filter { it.victim == farAttacker }).isEmpty()
        assertThat(sim.dealt.filter { it.victim == attacker }).describedAs("window closes after the first parry").hasSize(1)
    }

    @Test
    fun `parry does not apply after the window has closed`() {
        val sim = parrySim()
        val cast = sim.start("couture-rapier-def")
        sim.step(31)
        sim.incoming(attacker, 12.0)
        sim.step(45)
        assertThat(sim.kit.runner.isRunning(cast)).describedAs("cast ends once the window is gone").isFalse()
        assertThat(sim.dealt).isEmpty()
    }

    @Test
    fun `bastion charge is not granted with no enemy near`() {
        val sim = CastSim(victims = mapOf(attacker to Vec3(0.0, 0.0, 15.0)))
        sim.cast("bastion-greatsword-s1", 45)
        assertThat(sim.statuses.filter { it.third == "charge" }).isEmpty()
    }

    @Test
    fun `bastion charge is granted with an enemy in range`() {
        val sim = CastSim(victims = mapOf(attacker to Vec3(0.0, 0.0, 2.5)))
        sim.cast("bastion-greatsword-s1", 45)
        assertThat(sim.statuses.filter { it.third == "charge" }).hasSize(1)
    }
}
