package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Carry TNT (content/game-modes/carry-tnt.yaml, deliveries_to_win 3; the e2e image shortens the shield to 40 HP and the
 * post-delivery respawn to 5 s): the red bot walks (A*) into tnt-spawn and stands there until the shield breaks and the
 * pickup hold completes, holds every blue checkpoint in order, then holds the blue deliver pedestal. Three round trips win.
 */
class CarryTntPlaythroughE2eTest : ObjectivePlaythroughBase() {

    private val suite = "Carry TNT playthrough"
    private val CHECKPOINT_DWELL_MS = 3_800L
    private val DELIVER_DWELL_MS = 2_800L

    @Test
    fun `carry-tnt is played until a team delivers the TNT three times`() {
        val ctx = startMatch(suite, "carry-tnt", "Tn") ?: return
        val (tx, tz) = ctx.zone("tnt-spawn")
        val (dx, dz) = ctx.zone("deliver-blue")
        val checkpoints = ctx.objectives.zoneNames("checkpoint-blue-").sorted().map { ctx.zone(it) }
        val a = ctx.actor
        val others = listOf(ctx.idle)
        E2eReport.check(suite, "the stage has a blue deliver pedestal and at least one blue checkpoint", "content/stages") {
            assertThat(checkpoints).describedAs("checkpoint-blue-* zones").isNotEmpty()
            "checkpoints=${checkpoints.size}"
        }

        for (round in 1..GOAL) {
            if (matchLines().isEmpty()) break
            val toTnt = ctx.walker.walkTo(tx, tz, 1.2, 120_000, others)
            walkCheck(ctx, "the TNT spawn (round $round)", toTnt, tx, tz)
            // Standing in the zone wears the shield down; the pickup is a hold afterwards.
            val pickedUp = ctx.walker.hold(60_000, others) { ctx.chatCount("picked up the TNT") >= round || matchLines().isEmpty() }
            E2eReport.check(suite, "standing in tnt-spawn breaks the shield and picks the TNT up ($round/$GOAL)", "SystemChat") {
                assertThat(pickedUp).describedAs("no pickup; pos=${posOf(a)} chat=${ctx.chat().takeLast(5)} sidebar=${sidebar(a)}").isTrue()
                "chat=${ctx.chat().filter { it.contains("TNT") }.takeLast(3)}"
            }

            for ((cx, cz) in checkpoints) {
                ctx.walker.walkTo(cx, cz, 1.2, 120_000, others) { ctx.chatCount("delivered the TNT") >= round }
                ctx.walker.hold(CHECKPOINT_DWELL_MS, others) { false }
            }
            ctx.walker.walkTo(dx, dz, 1.5, 120_000, others) { ctx.chatCount("delivered the TNT") >= round }
            ctx.walker.hold(DELIVER_DWELL_MS, others) { ctx.chatCount("delivered the TNT") >= round || matchLines().isEmpty() }
            val delivered = a.waitUntil(6_000) { ctx.chatCount("delivered the TNT") >= round || matchLines().isEmpty() }
            E2eReport.check(suite, "holding the checkpoints and then the enemy deliver pedestal scores a delivery ($round/$GOAL)", "SystemChat / sidebar") {
                assertThat(delivered).describedAs("no delivery; pos=${posOf(a)} chat=${ctx.chat().takeLast(5)} sidebar=${sidebar(a)}").isTrue()
                "chat=${ctx.chat().filter { it.contains("TNT") }.takeLast(3)}; sidebar=${sidebar(a)}"
            }
        }
        finishMatch(ctx)
    }

    private companion object {
        const val GOAL = 3
    }
}
