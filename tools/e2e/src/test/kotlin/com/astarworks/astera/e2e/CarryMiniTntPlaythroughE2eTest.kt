package com.astarworks.astera.e2e

import com.astarworks.astera.bots.ObjectiveWalker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Carry Mini TNT (content/game-modes/carry-mini-tnt.yaml: pieces are planted in the ENEMY base, 10 planted pieces blow it up and win;
 * the e2e image regrows the spots after 3 s): the red bot walks (A*) laps over the three pickup spots and carries each load into the
 * blue base until the match ends.
 */
class CarryMiniTntPlaythroughE2eTest : ObjectivePlaythroughBase() {

    private val suite = "Carry Mini TNT playthrough"

    @Test
    fun `carry-mini-tnt is played from collecting pieces to planting them in the enemy base`() {
        val ctx = startMatch(suite, "carry-mini-tnt", "Mt") ?: return
        val spots = listOf("wool-b", "wool-a", "wool-c").map { ctx.zone(it) }
        val (bx, bz) = ctx.zone("base-blue")
        val a = ctx.actor
        val others = listOf(ctx.idle)

        var laps = 0
        while (ctx.chatCount("blew up") < 1 && laps < MAX_LAPS) {
            laps++
            for ((sx, sz) in spots) ctx.walker.walkTo(sx, sz, 1.2, 120_000, others) { ctx.chatCount("blew up") >= 1 }
            ctx.walker.walkTo(bx, bz, 2.0, 120_000, others) { ctx.chatCount("blew up") >= 1 }
            a.waitUntil(3_000) { ctx.chatCount("planted", a) >= laps || ctx.chatCount("blew up") >= 1 }
        }
        E2eReport.check(suite, "collecting pieces and planting them in the enemy base (plant progress on chat and sidebar)", "SystemChat / sidebar") {
            assertThat(ctx.chatCount("mini-TNT picked up")).describedAs("no piece picked up; chat=${ctx.chat().takeLast(6)}").isGreaterThanOrEqualTo(3)
            assertThat(ctx.chatCount("planted")).describedAs("nothing planted; pos=${posOf(a)} chat=${ctx.chat().takeLast(6)}").isGreaterThanOrEqualTo(1)
            "laps=$laps planted=${ctx.chat().filter { it.contains("planted") }}; sidebar=${sidebar(a)}"
        }
        val blown = a.waitUntil(8_000) { ctx.chatCount("blew up") >= 1 || matchLines().isEmpty() }
        E2eReport.check(suite, "planting enough mini-TNT blows up the enemy base and wins the match", "SystemChat") {
            assertThat(blown).describedAs("enemy base never blown up after $laps laps; pos=${posOf(a)} chat=${ctx.chat().takeLast(8)} sidebar=${sidebar(a)}").isTrue()
            "chat=${ctx.chat().filter { it.contains("blew up") }}"
        }
        finishMatch(ctx)
    }

    private companion object {
        const val MAX_LAPS = 6
    }
}
