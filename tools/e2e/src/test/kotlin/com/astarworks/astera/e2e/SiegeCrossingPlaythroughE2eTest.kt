package com.astarworks.astera.e2e

import com.astarworks.astera.bots.ObjectiveWalker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.floor

/**
 * First minutes of a siege-isles match (break-core lane) played the way a newcomer would: the red bot queues, spawns on its base
 * island, WALKS (A* over the island/bridge route graph, no teleports) across the bridge to the centre island and on over the blue
 * bridge to the front of the blue core, with the Norma progress lines on the sidebar the whole way. It breaks the core with a pickaxe
 * and the match ends with VICTORY for the walker and DEFEAT for the bot that stood still.
 *
 * [SiegePlaythroughE2eTest] covers the build and the core rules with teleports; this one proves the map is traversable on foot.
 * The e2e image bakes the compact copy of the stage (docker/e2e-tune-content.sh), so the production-size islands are not walked here.
 */
class SiegeCrossingPlaythroughE2eTest : ObjectivePlaythroughBase() {

    private val suite = "Siege crossing playthrough"

    private fun levelLine(b: Bot): String? = sidebar(b).firstOrNull { Regex("Lv\\s*\\d+/\\d+").containsMatchIn(it) }
    private fun goalLine(b: Bot): String? = sidebar(b).firstOrNull { it.contains("Goal", ignoreCase = true) }

    @Test
    fun `bots cross the siege islands on foot and the red bot breaks the blue core`() {
        val ctx = startMatch(suite, "break-core", "Sx") ?: return
        val a = ctx.actor
        val others = listOf(ctx.idle)

        E2eReport.check(suite, "the sidebar shows the cores and the Norma progress (level, goal) from the first second", "SetScore (sidebar)") {
            val lines = sidebar(a)
            assertThat(lines.count { it.contains("intact", true) }).describedAs("core lines: $lines").isEqualTo(2)
            assertThat(levelLine(a)).describedAs("no Norma level line: $lines").isNotNull()
            assertThat(goalLine(a)).describedAs("no Norma goal line: $lines").isNotNull()
            quote(lines)
        }

        // Leg 1: red base island -> bridge -> centre island (stage-local origin).
        val cx = ctx.originX + 0.5
        val cz = ctx.originZ + 0.5
        val toCentre = ctx.walker.walkTo(cx, cz, 3.0, 120_000, others)
        walkCheck(ctx, "the centre island", toCentre, cx, cz)
        E2eReport.check(suite, "the walker is on the centre island, past the red bridge", "RCON data get Pos") {
            val local = localPosOf(a)
            assertThat(toCentre).isEqualTo(ObjectiveWalker.Result.ARRIVED)
            assertThat(local).describedAs("no position").isNotNull()
            assertThat(abs(local!!.first)).describedAs("not at the centre island: $local").isLessThan(8.0)
            assertThat(islandStanceProblem(a)).describedAs("not standing on an island block").isNull()
            "local pos=$local; sidebar=${levelLine(a)} / ${goalLine(a)}"
        }

        // Leg 2: centre island -> bridge -> the open front of the blue core.
        val (coreX, coreZ) = ctx.zone("core-blue")
        val frontX = coreX - 2.5
        val frontZ = coreZ + 0.5
        val toCore = ctx.walker.walkTo(frontX, frontZ, 1.2, 120_000, others)
        walkCheck(ctx, "the front of the blue core", toCore, frontX, frontZ)
        E2eReport.check(suite, "the walker stands in the blue base, in front of the core", "RCON data get Pos") {
            assertThat(toCore).isEqualTo(ObjectiveWalker.Result.ARRIVED)
            assertThat(matchLines()).describedAs("match ended before the core was reached").isNotEmpty()
            "local pos=${localPosOf(a)}; sidebar=${levelLine(a)} / ${goalLine(a)}"
        }

        // Break the core: haste + diamond pickaxe, same tool setup as SiegePlaythroughE2eTest.
        rcon("effect give ${a.name} minecraft:haste 600 100 true")
        rcon("item replace entity ${a.name} hotbar.7 with minecraft:diamond_pickaxe")
        a.selectHotbar(7)
        Thread.sleep(300)
        a.dig(floor(coreX).toInt(), 65, floor(coreZ).toInt())
        val fell = a.waitUntil(5_000) { sidebar(a).any { it.contains("core down", true) } || matchLines().isEmpty() }
        E2eReport.check(suite, "breaking the enemy core after the walk eliminates that team", "SetScore (sidebar) / chat") {
            assertThat(fell).describedAs("core never fell; pos=${localPosOf(a)} sidebar=${sidebar(a)} chat=${ctx.chat(a).takeLast(5)}").isTrue()
            "chat=${ctx.chat(a).takeLast(3)}"
        }
        finishMatch(ctx, endTimeoutMs = 40_000)
    }
}
