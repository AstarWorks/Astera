package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The bastion greatsword skills keep their approved particle density (founder 10/31: "great sword quality got worse"). Check 16 (caster-eye clutter)
 * had been met by thinning the blade rows (-38% / -34% particle events); it is met by where and how the rows emit, never by deleting them, and nothing
 * is hidden from the caster. This casts each skill through the real wiring (the real Paper particle gateway recorder) and pins the number of
 * particle calls at the approved density, so a later thinning fails here.
 */
class BastionGreatswordFullDensityScenarioTest {
    private fun particleCalls(skill: String): Int {
        var n = 0
        scenario("bastion-density-$skill") {
            val caster = player("caster", team = "red")
            val target = mob("dummy", at = Vec3(0.0, groundY, 4.0), team = "blue")
            caster.holds("bastion-greatsword")
            caster.faces(target)
            sim.setMaxHealth(target, 5000.0)
            caster.cast(skill)
            advance(80)
            assertThat(sim.skillEngine.problems).describedAs("$skill engine problems").isEmpty()
            n = sim.recorder.particleCalls().size
        }
        return n
    }

    @Test
    fun s1KeepsTheApprovedDensity() {
        assertThat(particleCalls("bastion-greatsword-s1")).describedAs("bastion-greatsword-s1 particle calls").isGreaterThanOrEqualTo(FLOOR_S1)
    }

    @Test
    fun s2KeepsTheApprovedDensity() {
        assertThat(particleCalls("bastion-greatsword-s2")).describedAs("bastion-greatsword-s2 particle calls").isGreaterThanOrEqualTo(FLOOR_S2)
    }

    private companion object {
        const val FLOOR_S1 = 900
        const val FLOOR_S2 = 1053
    }
}
