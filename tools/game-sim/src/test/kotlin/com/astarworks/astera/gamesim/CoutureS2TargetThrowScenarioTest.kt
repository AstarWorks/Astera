package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

/**
 * ADR-0287: S2 coin 2 is a real projectile (`couture-rapier-s2-shears`). The burst and the stuck shears exist only after a real
 * hit, at the real target, and follow it when it moves; a shot with nothing in line produces neither. Real wiring, real shipped content.
 */
class CoutureS2TargetThrowScenarioTest {

    private fun isRapier(spawned: SimDisplayGateway.Spawned): Boolean =
        (spawned.spec.kind as? DisplayKind.Item)?.itemModel == "astera:couture-rapier"

    private fun hitAt(distance: Double) {
        scenario("couture-s2-shears-hit-$distance") {
            val caster = player("caster", team = "red")
            val target = player("target", at = Vec3(0.0, groundY, distance), team = "blue")
            val castTick = sim.tick
            caster.cast("couture-rapier-s2")
            advance(52)

            // flying shears (riding the projectile) + the stuck shears spawned on the target after the hit
            val rapiers = sim.displays.spawned.filter { it.tick - castTick >= 30 && isRapier(it) }
            assertThat(rapiers).describedAs("flying + stuck shears at $distance blocks").hasSizeGreaterThanOrEqualTo(2)
            val stuck = rapiers.last()
            assertThat(stuck.at.z).describedAs("stuck shears spawn at the real target").isCloseTo(distance, Offset.offset(0.6))

            // the burst spawns at the target
            val burst = sim.displays.spawned.filter { it.tick - castTick >= 34 && Math.abs(it.at.z - distance) < 0.6 }
            assertThat(burst).describedAs("burst displays at the target, $distance blocks").hasSizeGreaterThan(5)

            // the stuck shears move with the target
            target.position = Vec3(0.0, groundY, distance + 1.5)
            advance(3)
            val lastTp = sim.displays.teleports.lastOrNull { it.handle == stuck.handle }?.at?.z
            assertThat(lastTp).describedAs("stuck shears follow the target").isCloseTo(distance + 1.5, Offset.offset(0.6))
        }
    }

    @Test
    fun `the shears hit a target 3 blocks away and stay stuck`() = hitAt(3.0)

    @Test
    fun `the shears hit a target 7 blocks away and stay stuck`() = hitAt(7.0)

    @Test
    fun `a shot with nothing in line shows no burst and no stuck shears`() {
        scenario("couture-s2-shears-miss") {
            val caster = player("caster", team = "red")
            player("bystander", at = Vec3(8.0, groundY, 4.0), team = "blue")
            val castTick = sim.tick
            caster.cast("couture-rapier-s2")
            advance(62)
            val late = sim.displays.spawned.filter { it.tick - castTick in 34..61 }
            assertThat(late.filter { isRapier(it) }).describedAs("only the flying shears, nothing stuck").hasSize(1)
            assertThat(late.filter { Math.abs(it.at.x) > 2.0 && Math.abs(it.at.z) > 2.0 && !isRapier(it) })
                .describedAs("no burst at the bystander").isEmpty()
        }
    }
}
