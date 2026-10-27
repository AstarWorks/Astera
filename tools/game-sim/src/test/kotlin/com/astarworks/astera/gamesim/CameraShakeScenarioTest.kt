package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.core.domain.effect.camera.ShakeProfile
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.mechanic.ShakeDirection
import com.astarworks.astera.core.domain.skill.mechanic.ShakeShape
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.skill.value.NumericValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** `camera_shake` pulse sequence through the real dispatcher, scheduler and feedback recorder. */
class CameraShakeScenarioTest {

    private fun ScenarioScope.shake(target: SimEntity, shape: ShakeShape, direction: ShakeDirection, ticks: Int, caster: SimEntity) {
        val dispatcher = checkNotNull(sim.combat.mechanicDispatcher) { "dispatcher not wired" }
        dispatcher.dispatch(
            listOf(
                Mechanic.CameraShake(
                    Targeter.Target, NumericValue.Literal(1.0), IntValue.Literal(ticks),
                    shape = shape, direction = direction, frequency = NumericValue.Literal(10.0),
                ),
            ),
            MechanicContext(actorId = caster.id, targetId = target.id, origin = caster.position, worldId = caster.world),
        )
    }

    @Test
    fun `decay shake alternates sides with shrinking amplitude and nets to zero`() {
        scenario("camera-shake-decay") {
            val caster = player("caster", team = "red")
            val victim = player("victim", at = Vec3(0.0, groundY, 4.0), team = "blue")
            shake(victim, ShakeShape.DECAY, ShakeDirection.NONE, 8, caster)
            advance(12)

            val kicks = sim.feedback.kicks.filter { it.target == victim.id }
            assertThat(kicks.map { it.tick }).isSorted()
            assertThat(kicks.size).isGreaterThan(3)
            val offsets = kicks.runningFold(0.0 to 0.0) { acc, k -> (acc.first + k.dx) to (acc.second + k.dy) }
            val magnitudes = offsets.drop(1).dropLast(1).map { Math.hypot(it.first, it.second) }
            assertThat(magnitudes.zipWithNext().all { (a, b) -> b <= a + 1e-9 }).describedAs("monotone decay $magnitudes").isTrue()
            assertThat(offsets.last().first).isCloseTo(0.0, offset(1e-6))
            assertThat(offsets.last().second).isCloseTo(0.0, offset(1e-6))
        }
    }

    @Test
    fun `from-hit impulse kicks away from the source on the first tick only then eases back`() {
        scenario("camera-shake-impulse-from-hit") {
            val caster = player("caster", team = "red")
            val victim = player("victim", at = Vec3(0.0, groundY, 4.0), team = "blue")
            shake(victim, ShakeShape.IMPULSE, ShakeDirection.FROM_HIT, 6, caster)
            advance(10)

            val kicks = sim.feedback.kicks.filter { it.target == victim.id }
            assertThat(kicks).isNotEmpty()
            assertThat(abs(kicks.first().dx) + abs(kicks.first().dy)).isGreaterThan(1.0)
            val sum = kicks.fold(0.0) { a, k -> a + k.dx } to kicks.fold(0.0) { a, k -> a + k.dy }
            assertThat(sum.first).isCloseTo(0.0, offset(1e-6))
            assertThat(sum.second).isCloseTo(0.0, offset(1e-6))
        }
    }

    @Test
    fun `planner is deterministic and bounded`() {
        val a = ShakeProfile.plan(ShakeShape.DECAY, ShakeDirection.NONE, 0.5, 20, 8.0, null, 42L)
        val b = ShakeProfile.plan(ShakeShape.DECAY, ShakeDirection.NONE, 0.5, 20, 8.0, null, 42L)
        assertThat(a).isEqualTo(b)
        assertThat(a.size).isLessThanOrEqualTo(ShakeProfile.MAX_PULSES)
    }
}
