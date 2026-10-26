package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Founder playtest regression: a player whose connection drops in a running match and who logs in again (through
 * Velocity, like every real client) is back in that match on their own team - not in the lobby.
 *
 * On this single `all` backend the lobby and the match share a server, so the check is that the returning login is
 * resumed (team side of the arena, slot-local, match sidebar, match still listed) instead of staying a lobby login. The
 * lobby -> game hop for the split topology is covered by `MidMatchReconnectScenarioTest` (two simulated servers).
 */
class ReconnectE2eTest : PlaythroughBase() {

    private val suite = "Mid-match reconnect"

    private fun side(b: Bot): String? = localPosOf(b)?.let { if (it.first < 0) "red" else "blue" }

    @Test
    fun `a player who drops out of a running match comes back onto their team`() {
        val logFrom = logLineCount()
        val a = bot("Rc1", PackPolicy.DECLINE)
        val b = bot("Rc2", PackPolicy.DECLINE)
        listOf(a, b).forEach { bot -> bot.waitUntil(15_000) { bot.sidebarLines().isNotEmpty() && bot.hotbar()[8] != -1 } }
        Thread.sleep(1_500)
        val lobbyTitle = a.sidebarTitle()

        a.command("astera queue join skirmish")
        b.command("astera queue join skirmish")
        val active = awaitMatch("ACTIVE", 60_000, keepAlive = listOf(a, b))
        E2eReport.check(suite, "two queued players get an ACTIVE match", "RCON astera match list") {
            assertThat(active).describedAs("no ACTIVE match; list=${matchLines()}").isNotNull()
            "line='${active?.raw}'"
        }
        if (active == null) return
        Thread.sleep(2_000)
        val sideBefore = side(b)
        val posBefore = localPosOf(b)

        // --- the drop ---------------------------------------------------------------------------------------------
        val name = b.name
        b.close()
        Thread.sleep(3_000)
        a.nudge()
        E2eReport.check(suite, "the match keeps running while the player is away", "RCON astera match list") {
            val lines = matchLines()
            assertThat(lines.map { it.id }).describedAs("match vanished after the drop: $lines").contains(active.id)
            assertThat(lines.first { it.id == active.id }.phase).isEqualToIgnoringCase("ACTIVE")
            "line='${lines.first { it.id == active.id }.raw}'"
        }

        // --- the login again (same name, fresh connection) ----------------------------------------------------------
        val back = bot("Rc2", PackPolicy.DECLINE, exactName = name)
        val onMatchSidebar = back.waitUntil(20_000) { back.sidebarTitle() != null && back.sidebarTitle() != lobbyTitle }
        Thread.sleep(2_500)
        E2eReport.check(suite, "the returning player sees the match sidebar, not the lobby one", "SetObjective/SetScore") {
            val lines = sidebar(back)
            assertThat(onMatchSidebar).describedAs("sidebar stayed '${back.sidebarTitle()}' (lobby='$lobbyTitle')").isTrue()
            assertThat(lines.count { it.contains("Red", true) || it.contains("Blue", true) })
                .describedAs("no team lines on the sidebar: $lines").isGreaterThanOrEqualTo(2)
            assertThat(rawKeys(lines)).isEmpty()
            quote(lines)
        }
        E2eReport.check(suite, "the returning player is on their own side of the arena, in the same team", "RCON data get Pos") {
            val pos = localPosOf(back)
            assertThat(sideBefore).describedAs("could not read the position before the drop: $posBefore").isNotNull()
            assertThat(side(back)).describedAs("came back on the other side / the lobby: before=$posBefore after=$pos").isEqualTo(sideBefore)
            assertThat(Math.abs(pos!!.first)).describedAs("still at the lobby spawn (arena spawns are at |local x| ~ 70, the lobby is another slot): $pos").isGreaterThan(25.0)
            assertThat(islandStanceProblem(back)).describedAs("the returning player is not standing on an arena island of the match slot").isNull()
            "before=$posBefore after=$pos"
        }
        E2eReport.check(suite, "the returning player plays again and the match still lists it as the same single match", "RCON playerGameType + astera match list") {
            val gt = gameTypeOf(back)
            assertThat(gt).describedAs("game type $gt (3 = spectator)").isIn(0, 2)
            val lines = matchLines()
            assertThat(lines.map { it.id }).describedAs("match list: $lines").containsExactly(active.id)
            assertThat(back.disconnectReason).isNull()
            "gameType=$gt; line='${lines.first().raw}'"
        }
        E2eReport.check(suite, "server log stayed free of errors", "server log") {
            val errs = serverErrorsSince(logFrom)
            assertThat(errs).describedAs("server errors:\n${quote(errs, 20)}").isEmpty()
            "clean"
        }
    }
}
