package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * MODE-14: a player who drops out of a running 1v1 and does not come back within the 60 s window forfeits; the
 * match ends with the remaining player's team winning and the bot that stayed sees VICTORY. (Coming back within
 * the window is `ReconnectE2eTest`; stars bursting and the roster/stat details are `MidMatchLeaveScenarioTest`.)
 */
class MidMatchLeaveE2eTest : PlaythroughBase() {

    private val suite = "Mid-match leave"

    @Test
    fun `a player who does not return within the window forfeits and the other team wins`() {
        val logFrom = logLineCount()
        val a = bot("Lv1", PackPolicy.DECLINE)
        val b = bot("Lv2", PackPolicy.DECLINE)
        listOf(a, b).forEach { bot -> bot.waitUntil(15_000) { bot.sidebarLines().isNotEmpty() && bot.hotbar()[8] != -1 } }
        a.command("astera queue join norma")
        b.command("astera queue join norma")
        val active = awaitMatch("ACTIVE", 60_000, keepAlive = listOf(a, b))
        E2eReport.check(suite, "two queued players get an ACTIVE match", "RCON astera match list") {
            assertThat(active).describedAs("no ACTIVE match; list=${matchLines()}").isNotNull()
            "line='${active?.raw}'"
        }
        if (active == null) return
        Thread.sleep(2_000)

        b.close()
        Thread.sleep(5_000)
        a.nudge()
        E2eReport.check(suite, "the match is still ACTIVE while the seat is held", "RCON astera match list") {
            assertThat(matchLines().firstOrNull { it.id == active.id }?.phase).isEqualToIgnoringCase("ACTIVE")
            "held"
        }

        val over = awaitNoMatch(80_000, keepAlive = listOf(a))
        E2eReport.check(suite, "after the window the match ends (the stayer's team wins)", "RCON astera match list") {
            assertThat(over).describedAs("match still listed 85 s after the drop: ${matchLines()}").isTrue()
            "ended"
        }
        E2eReport.check(suite, "server log stayed free of errors", "server log") {
            val errs = serverErrorsSince(logFrom)
            assertThat(errs).describedAs("server errors:\n${quote(errs, 20)}").isEmpty()
            "clean"
        }
    }
}
