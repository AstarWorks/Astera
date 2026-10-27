package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Steal Jewelry (gems_per_team 6, steal channel 4 s, 5 banked jewels = knockout): the red bot walks (A*) into the blue jewelry pedestal zone (steal 1),
 * steps out to the middle of the blue base and back in for steals 2 to 5, then carries all five into its own base zone: banked 5/5 wins.
 */
class StealJewelryPlaythroughE2eTest : ObjectivePlaythroughBase() {

    private val suite = "Steal Jewelry playthrough"
    private val DWELL_MS = 4_800L

    @Test
    fun `steal-jewelry is played until a team banks every enemy jewel`() {
        val ctx = startMatch(suite, "steal-jewelry", "Sj") ?: return
        val a = ctx.actor
        val others = listOf(ctx.idle)
        val (jx, jz) = ctx.zone("jewelry-blue")
        val (ox, oz) = ctx.zone("base-blue")
        val (hx, hz) = ctx.zone("base-red")

        val toJewel = ctx.walker.walkTo(jx, jz, 0.8, 150_000, others)
        walkCheck(ctx, "the blue jewelry pedestal", toJewel, jx, jz)
        Thread.sleep(DWELL_MS) // stealing is a 4 s hold (steal_channel_ticks): stand on the pedestal until the bar completes
        val first = a.waitUntil(8_000) { ctx.chatCount("stole a jewel") >= 1 }
        E2eReport.check(suite, "entering the enemy pedestal zone steals a jewel (1)", "SystemChat / sidebar") {
            assertThat(first).describedAs("no theft message; pos=${posOf(a)} chat=${ctx.chat().takeLast(5)} sidebar=${sidebar(a)}").isTrue()
            "chat=${ctx.chat().filter { it.contains("jewel") }}; sidebar=${sidebar(a)}"
        }

        for (n in 2..5) {
            ctx.walker.walkTo(ox, oz, 1.5, 60_000, others)
            ctx.walker.walkTo(jx, jz, 0.8, 60_000, others)
            Thread.sleep(DWELL_MS)
            val stole = a.waitUntil(8_000) { ctx.chatCount("stole a jewel") >= n }
            E2eReport.check(suite, "re-entering the pedestal zone steals jewel $n", "SystemChat / sidebar") {
                assertThat(stole).describedAs("no theft $n; pos=${posOf(a)} chat=${ctx.chat().takeLast(5)} sidebar=${sidebar(a)}").isTrue()
                "thefts=${ctx.chatCount("stole a jewel")}; sidebar=${sidebar(a)}"
            }
        }

        ctx.walker.walkTo(hx, hz, 2.0, 150_000, others) { ctx.chatCount("banked") >= 1 || matchLines().isEmpty() }
        val banked = a.waitUntil(8_000) { ctx.chatCount("banked") >= 1 || matchLines().isEmpty() }
        E2eReport.check(suite, "carrying the jewels into the own base banks them (5/5) and wins", "SystemChat") {
            assertThat(banked).describedAs("nothing banked; pos=${posOf(a)} chat=${ctx.chat().takeLast(6)} sidebar=${sidebar(a)}").isTrue()
            "chat=${ctx.chat().filter { it.contains("banked") }}"
        }
        finishMatch(ctx)
    }
}
