package com.astarworks.astera.e2e

import com.astarworks.astera.bots.ObjectiveWalker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Carry Mini TNT (content/game-modes/carry-mini-tnt.yaml: 10 pooled pieces combine into one TNT, 1 delivered TNT wins; the e2e
 * image regrows the spots after 3 s): the red bot walks (A*) laps over the three pickup spots and banks each load at its own base
 * until the pool reaches 10 and the pieces combine, then picks the combined TNT up at tnt-spawn and delivers it to the blue base.
 */
class CarryMiniTntPlaythroughE2eTest : ObjectivePlaythroughBase() {

    private val suite = "Carry Mini TNT playthrough"

    @Test
    fun `carry-mini-tnt is played from collecting pieces to combining 10 into a TNT and delivering it`() {
        val ctx = startMatch(suite, "carry-mini-tnt", "Mt") ?: return
        val spots = listOf("wool-b", "wool-a", "wool-c").map { ctx.zone(it) }
        val (tx, tz) = ctx.zone("tnt-spawn")
        val (homeX, homeZ) = ctx.zone("base-red")
        val (bx, bz) = ctx.zone("base-blue")
        val a = ctx.actor
        val others = listOf(ctx.idle)

        var laps = 0
        while (ctx.chatCount("combined") < 1 && laps < MAX_LAPS) {
            laps++
            for ((sx, sz) in spots) ctx.walker.walkTo(sx, sz, 1.2, 120_000, others) { ctx.chatCount("combined") >= 1 }
            ctx.walker.walkTo(homeX, homeZ, 2.0, 120_000, others) { ctx.chatCount("combined") >= 1 }
            a.waitUntil(3_000) { ctx.chatCount("added", a) >= laps }
        }
        E2eReport.check(suite, "collecting pieces and banking them at the own base fills the pool (pool progress on chat and sidebar)", "SystemChat / sidebar") {
            assertThat(ctx.chatCount("mini-TNT picked up")).describedAs("no piece picked up; chat=${ctx.chat().takeLast(6)}").isGreaterThanOrEqualTo(3)
            assertThat(ctx.chatCount("to its pool")).describedAs("nothing banked; pos=${posOf(a)} chat=${ctx.chat().takeLast(6)}").isGreaterThanOrEqualTo(1)
            "laps=$laps pooled=${ctx.chat().filter { it.contains("to its pool") }}; sidebar=${sidebar(a)}"
        }
        val combined = a.waitUntil(6_000) { ctx.chatCount("combined 10 mini-TNT") >= 1 }
        E2eReport.check(suite, "10 pooled pieces combine into one TNT (combine broadcast)", "SystemChat") {
            assertThat(combined).describedAs("never combined after $laps laps; pos=${posOf(a)} chat=${ctx.chat().takeLast(8)} sidebar=${sidebar(a)}").isTrue()
            "chat=${ctx.chat().filter { it.contains("combined") }}"
        }

        val toTnt = ctx.walker.walkTo(tx, tz, 1.2, 120_000, others) { ctx.chatCount("picked up the combined TNT") >= 1 }
        walkCheck(ctx, "the combined TNT", toTnt, tx, tz)
        val pickedUp = a.waitUntil(6_000) { ctx.chatCount("picked up the combined TNT") >= 1 }
        E2eReport.check(suite, "the owning team picks the combined TNT up at tnt-spawn", "SystemChat") {
            assertThat(pickedUp).describedAs("no TNT pickup; pos=${posOf(a)} chat=${ctx.chat().takeLast(5)} sidebar=${sidebar(a)}").isTrue()
            "chat=${ctx.chat().filter { it.contains("combined TNT") }}; sidebar=${sidebar(a)}"
        }

        ctx.walker.walkTo(bx, bz, 2.0, 120_000, others) { ctx.chatCount("delivered the combined TNT") >= 1 || matchLines().isEmpty() }
        val delivered = a.waitUntil(8_000) { ctx.chatCount("delivered the combined TNT") >= 1 || matchLines().isEmpty() }
        E2eReport.check(suite, "delivering the combined TNT to the enemy base wins the match", "SystemChat") {
            assertThat(delivered).describedAs("not delivered; pos=${posOf(a)} chat=${ctx.chat().takeLast(6)} sidebar=${sidebar(a)}").isTrue()
            "delivery=${ctx.chat().filter { it.contains("delivered") }}"
        }
        finishMatch(ctx)
    }

    private companion object {
        const val MAX_LAPS = 6
    }
}
