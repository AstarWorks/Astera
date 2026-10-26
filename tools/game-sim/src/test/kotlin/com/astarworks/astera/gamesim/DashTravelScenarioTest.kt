package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

/**
 * Caster travel of the shipped Bastion greatsword dashes (`dash_strike` through the real dispatcher and real
 * `content/skills`). Measured in the live server (vfx-shot, 2026-10-27): the runtime moves the caster exactly the
 * configured distance, so what a player sees as "too short" is the `stop_before_target` contact stop, which this test
 * pins down together with the burst shape (one uniform step per tick for 3 ticks, then a hard stop):
 *
 * - open field: every dash covers its configured distance, in 3 consecutive ticks, with the caster still in between;
 * - a target ahead: the caster never ends a dash closer than the skill's `stop_before_target` (no pass-through).
 */
class DashTravelScenarioTest {

    /** One burst: the sim ticks (1-based, after the cast) on which the caster moved, and the distance covered. */
    private data class Burst(val firstTick: Int, val ticks: Int, val distance: Double, val endTick: Int)

    private fun bursts(zs: List<Double>): List<Burst> {
        val result = mutableListOf<Burst>()
        var i = 1
        while (i < zs.size) {
            if (zs[i] - zs[i - 1] > EPS) {
                var j = i
                while (j + 1 < zs.size && zs[j + 1] - zs[j] > EPS) j++
                result += Burst(firstTick = i, ticks = j - i + 1, distance = zs[j] - zs[i - 1], endTick = j)
                i = j + 1
            } else i++
        }
        return result
    }

    /** The caster's z after each of [total] ticks following the cast (index 0 = right after the cast call). */
    private fun ScenarioScope.casterTrace(skill: String, total: Int, caster: SimEntity): List<Double> {
        // Cast with the skill's own weapon: the starter kit's pistol would spend its ammo pool and reject the cast.
        caster.holds("bastion-greatsword")
        caster.cast(skill)
        val zs = mutableListOf(caster.position.z)
        repeat(total) {
            advance(1)
            zs += caster.position.z
        }
        return zs
    }

    @Test
    fun `S2 open field travels 1_2 then 0_8 then 2_0 in three-tick bursts with hard stops`() {
        scenario("dash-travel-s2-open") {
            val caster = player("caster", team = "red")
            val zs = casterTrace("bastion-greatsword-s2", 90, caster)
            val b = bursts(zs)

            assertThat(b).hasSize(3)
            assertThat(b.map { it.ticks }).containsExactly(3, 3, 3)
            assertThat(b[0].distance).isCloseTo(1.2, TOLERANCE)
            assertThat(b[1].distance).isCloseTo(0.8, TOLERANCE)
            assertThat(b[2].distance).isCloseTo(2.0, TOLERANCE)
            // delay_ticks 10 / 29 / 54: the rings are 19 and 25 ticks apart, and the caster is still in between.
            assertThat(b[1].firstTick - b[0].firstTick).isEqualTo(19)
            assertThat(b[2].firstTick - b[1].firstTick).isEqualTo(25)
            assertThat(zs.last() - zs.first()).isCloseTo(4.0, TOLERANCE)
        }
    }

    @Test
    fun `S1 open field travels 2_4 then 2_6 in three-tick bursts`() {
        scenario("dash-travel-s1-open") {
            val caster = player("caster", team = "red")
            val zs = casterTrace("bastion-greatsword-s1", 60, caster)
            val b = bursts(zs)

            assertThat(b).hasSize(2)
            assertThat(b.map { it.ticks }).containsExactly(3, 3)
            assertThat(b[0].distance).isCloseTo(2.4, TOLERANCE)
            assertThat(b[1].distance).isCloseTo(2.6, TOLERANCE)
            assertThat(b[1].firstTick - b[0].firstTick).isEqualTo(24)
        }
    }

    @Test
    fun `S2 never ends a dash closer to the target than its stop_before_target`() {
        scenario("dash-travel-s2-contact") {
            val caster = player("caster", team = "red")
            val target = mob("dummy", Vec3(0.0, groundY, 4.0), team = "blue")
            caster.holds("bastion-greatsword")
            caster.cast("bastion-greatsword-s2")
            val zs = mutableListOf(caster.position.z)
            val targetZ = mutableListOf(target.position.z)
            repeat(90) {
                advance(1)
                zs += caster.position.z
                targetZ += target.position.z
            }
            val b = bursts(zs)

            // The first ring still fits (4.0 - 1.7 = 2.3 >= 1.2), later bursts are shortened by the contact stop.
            assertThat(b).isNotEmpty()
            assertThat(b[0].distance).isCloseTo(1.2, TOLERANCE)
            val gaps = listOf(1.7, 1.7, 1.0)
            b.forEachIndexed { index, burst ->
                val remaining = targetZ[burst.endTick] - zs[burst.endTick]
                assertThat(remaining).isGreaterThanOrEqualTo(gaps[index] - 0.05)
            }
        }
    }

    private companion object {
        const val EPS = 1e-6
        val TOLERANCE: Offset<Double> = Offset.offset(0.01)
    }
}
