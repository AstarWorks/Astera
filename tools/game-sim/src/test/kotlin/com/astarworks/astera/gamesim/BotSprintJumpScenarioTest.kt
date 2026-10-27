package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.SprintJumpPolicy
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Long non-combat travel dash-jumps; combat movement and standing still never do. The decision is the live
 * [SprintJumpPolicy] (the object BotBrain calls); here a sim player walks a straight line at a fixed speed while the
 * policy and its jump arc drive the player's height tick by tick.
 */
class BotSprintJumpScenarioTest {

    private class Walk(val sprint: Boolean, val jump: Boolean, val y: Double)

    /** One walked tick: asks the policy, advances the jump arc like BotBrain.travel and moves the sim player. */
    private fun ScenarioScope.walk(
        bot: SimEntity,
        ticks: Int,
        inCombat: Boolean = false,
        moving: Boolean = true,
        groundAhead: Boolean = true,
        headroom: Boolean = true,
        speed: Double = SprintJumpPolicy.DASH_JUMP_SPEED,
    ): List<Walk> {
        val out = ArrayList<Walk>()
        var step = -1
        repeat(ticks) {
            val airborne = step >= 0
            val d = SprintJumpPolicy.decide(inCombat, moving, airborne, groundAhead, headroom, distance = 100.0, dashing = airborne)
            var lift = 0.0
            if (d.sprint && (airborne || d.jump)) {
                val s = if (airborne) step else 0
                lift = SprintJumpPolicy.lift(s)
                step = if (lift == 0.0) -1 else s + 1
            } else {
                step = -1
            }
            val dz = if (moving) d.speed else 0.0
            bot.moveTo(Vec3(bot.position.x, groundY + lift, bot.position.z + dz))
            advance(1)
            out += Walk(d.sprint, d.jump, bot.position.y - groundY)
        }
        return out
    }

    @Test
    fun `non-combat travel sprints and jumps on the ground, combat and standing still do not`() {
        scenario("bot-sprint-jump") {
            val bot = player("bot", team = "red")
            val z0 = bot.position.z
            val speedRef = SprintJumpPolicy.DASH_JUMP_SPEED

            val travel = walk(bot, 60)
            assertThat(travel).allMatch { it.sprint }
            assertThat(travel.count { it.jump }).`as`("jumps while travelling").isGreaterThanOrEqualTo(3)
            assertThat(travel.maxOf { it.y }).`as`("stays within the arc").isBetween(1.0, SprintJumpPolicy.PEAK + 1e-9)
            assertThat(travel.minOf { it.y }).isGreaterThanOrEqualTo(0.0)
            assertThat(travel.any { it.y == 0.0 }).`as`("lands between jumps").isTrue()
            assertThat(bot.position.z - z0).`as`("moved at the dash-jump speed").isCloseTo(60 * speedRef, org.assertj.core.data.Offset.offset(1e-6))

            val fight = walk(bot, 30, inCombat = true)
            assertThat(fight).allMatch { !it.sprint && !it.jump && it.y == 0.0 }

            val stopped = walk(bot, 20, moving = false)
            assertThat(stopped).allMatch { !it.sprint && !it.jump && it.y == 0.0 }

            val edge = walk(bot, 30, groundAhead = false)
            assertThat(edge).`as`("sprint towards an edge, but never a jump").allMatch { it.sprint && !it.jump && it.y == 0.0 }

            val ceiling = walk(bot, 30, headroom = false)
            assertThat(ceiling).allMatch { !it.jump && it.y == 0.0 }
        }
    }
}
