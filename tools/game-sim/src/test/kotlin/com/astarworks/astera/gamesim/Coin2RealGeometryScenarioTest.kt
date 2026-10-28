package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * SKL-08: the exact geometry of `SkillEngineCoin2E2eTest` (real Paper): a zombie box (0.6 x 1.95) 6 blocks ahead of a
 * caster looking along +Z, with a strict Paper-like broad phase and a ground plane. The default 1.6 wide sim box hid
 * lane misses.
 */
class Coin2RealGeometryScenarioTest {

    @Test
    fun `coin 2 throw hits a zombie sized dummy across the aim lane`() = lane(Vec3.ZERO, 0.0)

    /** The e2e caster stands at y -60, away from the world origin and turned: the throw must still hit. */
    @Test
    fun `coin 2 throw hits at a non zero caster origin and yaw`() = lane(Vec3(37.5, -60.0, -12.5), 30.0)

    private fun lane(origin: Vec3, yaw: Double) {
        val zombie = simVictim(1)
        val misses = ArrayList<String>()
        var dz = 4.0
        while (dz <= 9.0) {
            var dx = -0.5
            while (dx <= 0.5) {
                val sim = CastSim(mapOf(zombie to ahead(origin, yaw, dz, dx)), halfWidth = 0.3, boxHeight = 1.95, floorY = origin.y, origin = origin, yawDeg = yaw)
                sim.cast("couture-rapier-s2", 80)
                if (sim.dealt.none { it.victim == zombie && it.amount == 18.0 }) misses += "dz=%.1f dx=%.1f".format(dz, dx)
                dx += 0.1
            }
            dz += 0.5
        }
        assertThat(misses).describedAs("throw (18 damage) misses over the lane at $origin yaw $yaw").isEmpty()
    }
}
