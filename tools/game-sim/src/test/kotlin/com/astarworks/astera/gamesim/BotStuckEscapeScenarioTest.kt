package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.SprintJumpPolicy
import com.astarworks.astera.bots.StuckEscapePolicy
import com.astarworks.astera.bots.StuckEscapePolicy.Action
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A bot that wants to move but does not get anywhere escalates: hop, sidestep, re-plan, other side, dig (item 25a-c). The
 * policy is the live [StuckEscapePolicy] (the object BotBrain asks every travel tick); a sim player stays pinned behind a
 * wall while the sim clock runs, then moves once the escape worked. The 1-block step hop is [SprintJumpPolicy].
 */
class BotStuckEscapeScenarioTest {

    private fun ScenarioScope.pinnedActions(policy: StuckEscapePolicy, bot: SimEntity, seconds: Int): List<Action> {
        val out = ArrayList<Action>()
        var now = 0L
        repeat(seconds * 20) {
            val a = policy.update(now, bot.position.x, bot.position.z, wantsToMove = true)
            if (a != Action.NONE) out += a
            advance(1)
            now += 50
        }
        return out
    }

    @Test
    fun `stuck bot escalates through the escapes and progress resets the escalation`() {
        scenario("bot-stuck-escape") {
            val bot = player("bot", team = "red")
            val policy = StuckEscapePolicy()

            val actions = pinnedActions(policy, bot, seconds = 10)
            assertThat(actions.take(5)).containsExactly(Action.JUMP, Action.SIDESTEP_LEFT, Action.REPATH, Action.SIDESTEP_RIGHT, Action.BREAK_BLOCK)
            assertThat(actions.drop(5)).`as`("keeps varying instead of repeating the hop").doesNotContain(Action.JUMP)
            assertThat(actions.size).isGreaterThanOrEqualTo(6)

            // Moved far enough in a window: not stuck, escalation starts over.
            var now = 100_000L
            policy.update(now, bot.position.x, bot.position.z, true)
            now += 1_300
            assertThat(policy.update(now, bot.position.x + 3.0, bot.position.z, true)).isEqualTo(Action.NONE)
            assertThat(policy.stuckLevel).isZero()
            now += 1_300
            assertThat(policy.update(now, bot.position.x + 3.0, bot.position.z, true)).isEqualTo(Action.JUMP)

            // Standing on purpose (arrived / fighting) is never stuck.
            val calm = StuckEscapePolicy()
            repeat(200) { assertThat(calm.update(it * 50L, 0.0, 0.0, wantsToMove = false)).isEqualTo(Action.NONE) }
        }
    }

    @Test
    fun `a one block step up and a stuck escape make the bot hop, combat never does`() {
        val walk = SprintJumpPolicy.decide(inCombat = false, moving = true, airborne = false, groundAhead = false, headroom = true)
        assertThat(walk.jump).`as`("no floor ahead and no step: no hop").isFalse()
        val step = SprintJumpPolicy.decide(inCombat = false, moving = true, airborne = false, groundAhead = false, headroom = true, stepUp = true)
        assertThat(step.jump && step.sprint).`as`("step up: sprint-hop onto it").isTrue()
        val escape = SprintJumpPolicy.decide(inCombat = false, moving = true, airborne = false, groundAhead = false, headroom = true, forceJump = true)
        assertThat(escape.jump).isTrue()
        val low = SprintJumpPolicy.decide(inCombat = false, moving = true, airborne = false, groundAhead = true, headroom = false, stepUp = true)
        assertThat(low.jump).`as`("never into a ceiling").isFalse()
        val fight = SprintJumpPolicy.decide(inCombat = true, moving = true, airborne = false, groundAhead = true, headroom = true, stepUp = true, forceJump = true)
        assertThat(fight).isEqualTo(SprintJumpPolicy.Decision.NONE)
    }
}
