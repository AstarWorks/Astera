package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * The couture-rapier-s2 throw volley is a machine-level `world:` projectile. Its `volley.hit` handler belongs to the machine root, so it fires on
 * the cast clock after the windup state has been left: dmg_throw (18) lands on a mob standing where the throw is aimed and `couture.stab` plays.
 */
class CoutureThrowHitScenarioTest {
    @Test
    fun `the s2 throw volley hits a mob in front of the caster`() {
        for (z in listOf(3.0, 4.0, 5.0, 6.0)) {
            val trace = scenario("couture-throw-hit-$z") {
                val caster = player("caster", team = "red")
                val target = mob("dummy", at = Vec3(0.0, groundY, z), team = "blue")
                caster.holds("couture-rapier")
                caster.faces(target)
                sim.setMaxHealth(target, 500.0)
                caster.cast("couture-rapier-s2")
                advance(70)
                val dealt = eventsOf<DamageDealt>().filter { it.target == target.id }.map { it.resolved.finalAmount }
                assertThat(sim.skillEngine.problems).describedAs("z=$z engine problems").isEmpty()
                assertThat(dealt).describedAs("z=$z damage dealt").contains(18.0)
                assertThat(hp(target)).describedAs("z=$z hp").isLessThanOrEqualTo(500.0 - dealt.sum() + 0.001)
            }
            assertThat(Files.readString(trace)).describedAs("z=$z couture.stab plays").contains("couture.stab")
        }
    }
}
