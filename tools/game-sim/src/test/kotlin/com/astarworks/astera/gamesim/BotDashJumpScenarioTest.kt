package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.SprintJumpPolicy
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

/**
 * NPC (bot) dash-jump. The decisions are the live [SprintJumpPolicy] that BotBrain calls:
 * The dash-jump covers the player's sprint-jump ground speed and is used only for long trips; short moves keep the
 *   normal walking jump (hops for step-ups / stuck escapes only).
 */
class BotDashJumpScenarioTest {

    /** Vanilla player sprint-jump: about 7.1 blocks/s. */
    private val playerDashJump = 0.356

    private class Tick(val sprint: Boolean, val jump: Boolean, val y: Double)

    private fun ScenarioScope.travel(bot: SimEntity, ticks: Int, startDistance: Double, stepUp: Boolean = false): List<Tick> {
        val out = ArrayList<Tick>()
        var step = -1
        var dashing = false
        var remaining = startDistance
        repeat(ticks) {
            val airborne = step >= 0
            val d = SprintJumpPolicy.decide(
                inCombat = false, moving = remaining > 1.0, airborne = airborne, groundAhead = true, headroom = true,
                stepUp = stepUp && !airborne, distance = remaining, dashing = dashing,
            )
            var lift = 0.0
            if (airborne || d.jump) {
                val s = if (airborne) step else 0
                if (!airborne) dashing = d.sprint
                lift = SprintJumpPolicy.lift(s)
                step = if (lift == 0.0) -1 else s + 1
                if (step == -1) dashing = false
            }
            val dz = if (remaining > 1.0) d.speed else 0.0
            remaining -= dz
            bot.moveTo(Vec3(bot.position.x, groundY + lift, bot.position.z + dz))
            advance(1)
            out += Tick(d.sprint, d.jump, bot.position.y - groundY)
        }
        return out
    }

    @Test
    fun `long trips dash-jump at the player sprint-jump speed, short moves walk and only hop for steps`() {
        scenario("bot-dash-jump") {
            val bot = player("bot", team = "red")
            val z0 = bot.position.z

            val far = travel(bot, 40, startDistance = 200.0)
            assertThat(far).allMatch { it.sprint }
            assertThat(far.count { it.jump }).`as`("dash-jumps on a long trip").isGreaterThanOrEqualTo(2)
            assertThat((bot.position.z - z0) / 40).`as`("blocks per tick while dashing")
                .isCloseTo(playerDashJump, Offset.offset(0.02))

            val z1 = bot.position.z
            val near = travel(bot, 20, startDistance = 6.0)
            assertThat(near).`as`("a short move never dash-jumps").allMatch { !it.sprint && !it.jump && it.y == 0.0 }
            assertThat((bot.position.z - z1) / 20).`as`("walks at the walking speed").isLessThan(SprintJumpPolicy.WALK_SPEED + 1e-6)

            val hop = travel(bot, 10, startDistance = 6.0, stepUp = true)
            assertThat(hop.first().jump).`as`("a step-up hop is the normal walking jump").isTrue()
            assertThat(hop).allMatch { !it.sprint }
        }
    }
}
