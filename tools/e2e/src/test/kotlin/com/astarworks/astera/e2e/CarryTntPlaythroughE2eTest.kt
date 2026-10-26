package com.astarworks.astera.e2e

import com.astarworks.astera.bots.ObjectiveWalker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Carry TNT (content/game-modes/carry-tnt.yaml, deliveries_to_win 2): the red bot walks (A*) from its spawn into the tnt-spawn zone
 * (pickup), on into the blue base zone (delivery 1), back for the TNT and into the blue base again (delivery 2 = the win).
 */
class CarryTntPlaythroughE2eTest : ObjectivePlaythroughBase() {

    private val suite = "Carry TNT playthrough"

    @Test
    fun `carry-tnt is played until a team delivers the TNT twice`() {
        val ctx = startMatch(suite, "carry-tnt", "Tn") ?: return
        val (tx, tz) = ctx.zone("tnt-spawn")
        val (bx, bz) = ctx.zone("base-blue")
        val a = ctx.actor
        val others = listOf(ctx.idle)

        val toTnt = ctx.walker.walkTo(tx, tz, 1.2, 120_000, others)
        walkCheck(ctx, "the TNT spawn", toTnt, tx, tz)
        val pickedUp = a.waitUntil(6_000) { ctx.chatCount("picked up the TNT") >= 1 }
        E2eReport.check(suite, "entering the tnt-spawn zone picks the TNT up (objective event)", "SystemChat") {
            assertThat(pickedUp).describedAs("no pickup message; pos=${posOf(a)} chat=${ctx.chat().takeLast(5)} sidebar=${sidebar(a)}").isTrue()
            "chat=${ctx.chat().filter { it.contains("TNT") }}; sidebar=${sidebar(a)}"
        }

        val toBase = ctx.walker.walkTo(bx, bz, 2.0, 120_000, others) { ctx.chatCount("delivered the TNT") >= 1 }
        val delivered = a.waitUntil(6_000) { ctx.chatCount("delivered the TNT") >= 1 }
        E2eReport.check(suite, "carrying the TNT into the enemy base scores a delivery (1/2)", "SystemChat / sidebar") {
            assertThat(delivered).describedAs("no delivery after walk=$toBase; pos=${posOf(a)} chat=${ctx.chat().takeLast(5)} sidebar=${sidebar(a)}").isTrue()
            "chat=${ctx.chat().filter { it.contains("TNT") }}; sidebar=${sidebar(a)}"
        }

        // Second round trip: the TNT is loose again at its spawn.
        val again = ctx.walker.walkTo(tx, tz, 1.2, 120_000, others) { ctx.chatCount("picked up the TNT") >= 2 }
        a.waitUntil(6_000) { ctx.chatCount("picked up the TNT") >= 2 }
        ctx.walker.walkTo(bx, bz, 2.0, 120_000, others) { matchLines().isEmpty() || ctx.chatCount("delivered the TNT") >= 2 }
        val won = a.waitUntil(8_000) { ctx.chatCount("delivered the TNT") >= 2 || matchLines().isEmpty() }
        E2eReport.check(suite, "the second delivery reaches the goal (2/2) and wins the match", "SystemChat") {
            assertThat(again).isNotEqualTo(ObjectiveWalker.Result.TIMEOUT)
            assertThat(won).describedAs("goal not reached; pos=${posOf(a)} chat=${ctx.chat().takeLast(6)} sidebar=${sidebar(a)}").isTrue()
            "deliveries=${ctx.chat().filter { it.contains("delivered") }}"
        }
        finishMatch(ctx)
    }
}
