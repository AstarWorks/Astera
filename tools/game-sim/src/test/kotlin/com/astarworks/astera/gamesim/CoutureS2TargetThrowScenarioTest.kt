package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

/**
 * ADR-0286: the S2 throw (shears, stuck shears, chain, hit2 burst) is anchored to the real target, at any distance, follows
 * it, and a throw with nothing in range flies to max range with no link and no burst. Real wiring, real shipped content.
 */
class CoutureS2TargetThrowScenarioTest {

    private fun ScenarioScope.isModel(spawned: SimDisplayGateway.Spawned, model: String): Boolean =
        (spawned.spec.kind as? DisplayKind.Item)?.itemModel == model

    private fun ScenarioScope.lastTeleportZ(handle: com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway.DisplayHandle): Double? =
        sim.displays.teleports.lastOrNull { it.handle == handle }?.at?.z

    private fun throwAt(distance: Double) {
        scenario("couture-s2-throw-$distance") {
            val caster = player("caster", team = "red")
            val target = player("target", at = Vec3(0.0, groundY, distance), team = "blue")
            val castTick = sim.tick
            caster.cast("couture-rapier-s2")
            advance(44)

            // The flying shears landed on the target: authored 5.4 blocks ahead + (live target - authored target).
            val flying = sim.displays.spawned.last { it.tick - castTick >= 30 && isModel(it, "astera:couture-rapier") }
            assertThat(lastTeleportZ(flying.handle)).describedAs("flying shears offset at $distance blocks")
                .isCloseTo(distance - 5.4, Offset.offset(0.05))

            // The hit2 burst spawns at the target when the hit lands.
            val burst = sim.displays.spawned.filter { it.tick - castTick in 38..44 && Math.abs(it.at.z - distance) < 0.01 }
            assertThat(burst).describedAs("burst displays at the target, $distance blocks").hasSizeGreaterThan(5)

            // The chain exists and the stuck shears follow the target when it moves.
            assertThat(sim.displays.spawned.filter { (it.spec.kind as? DisplayKind.Block)?.blockKey?.contains("white_concrete") == true && it.tick - castTick >= 30 })
                .describedAs("chain line").isNotEmpty()
            advance(4)
            target.position = Vec3(0.0, groundY, distance + 1.5)
            advance(3)
            val stuck = sim.displays.spawned.last { isModel(it, "astera:couture-shears-linked") }
            assertThat(lastTeleportZ(stuck.handle)).describedAs("stuck shears follow the target")
                .isCloseTo(distance + 1.5 - 5.4, Offset.offset(0.05))
        }
    }

    @Test
    fun `the throw lands on a target 3 blocks away`() = throwAt(3.0)

    @Test
    fun `the throw lands on a target 7 blocks away`() = throwAt(7.0)

    @Test
    fun `a throw with nothing in range flies to max range with no link and no burst`() {
        scenario("couture-s2-throw-miss") {
            val caster = player("caster", team = "red")
            val castTick = sim.tick
            caster.cast("couture-rapier-s2")
            advance(62)
            val late = sim.displays.spawned.filter { it.tick - castTick in 30..61 }
            assertThat(late.filter { isModel(it, "astera:couture-shears-linked") }).isEmpty()
            assertThat(late.filter { (it.spec.kind as? DisplayKind.Block)?.blockKey?.contains("white_concrete") == true }).isEmpty()
            assertThat(late.filter { Math.abs(it.at.z) > 1.0 }).describedAs("no burst away from the caster").isEmpty()
            assertThat(late.filter { isModel(it, "astera:couture-rapier") }).describedAs("the shears still fly").isNotEmpty()
        }
    }
}
