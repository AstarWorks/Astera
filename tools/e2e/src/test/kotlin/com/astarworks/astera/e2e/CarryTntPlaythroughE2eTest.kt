package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Carry TNT (Rainmaker, content/game-modes/carry-tnt.yaml; the e2e image shortens the shield to 40 HP): the red bot walks (A*)
 * into tnt-spawn and stands there until the shield breaks, then right-clicks the TNT to pick it up, holds every blue checkpoint in
 * order, then holds the blue deliver pedestal. Reaching the goal is a knockout: one trip wins.
 */
class CarryTntPlaythroughE2eTest : ObjectivePlaythroughBase() {

    private val suite = "Carry TNT playthrough"
    private val CHECKPOINT_DWELL_MS = 3_800L
    private val DELIVER_DWELL_MS = 2_800L

    @Test
    fun `carry-tnt is played until a team carries the TNT into the enemy goal`() {
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

        E2eReport.check(suite, "carry-tnt hands out the navigation compass at hotbar index 8", "hotbar") {
            assertThat(a.hotbar()[8]).describedAs("hotbar index 8 holds the compass; hotbar=${a.hotbar().toList()}").isNotEqualTo(-1)
            "hotbar[8]=${a.hotbar()[8]}"
        }

        for (round in 1..GOAL) {
            if (matchLines().isEmpty()) break
            val toTnt = ctx.walker.walkTo(tx, tz, 1.2, 120_000, others)
            walkCheck(ctx, "the TNT spawn (round $round)", toTnt, tx, tz)
            // Standing in the zone wears the shield down, but no longer picks the TNT up: that needs a right-click.
            ctx.walker.hold(8_000, others) { false }
            E2eReport.check(suite, "standing in tnt-spawn does not pick the TNT up on its own ($round/$GOAL)", "SystemChat") {
                assertThat(ctx.chatCount("picked up the TNT")).describedAs("picked up without a click; chat=${ctx.chat().takeLast(5)}").isLessThan(round)
                "chat=${ctx.chat().filter { it.contains("TNT") }.takeLast(3)}"
            }
            val pickedUp = clickObjective(a, 30_000) { ctx.chatCount("picked up the TNT") >= round || matchLines().isEmpty() }
            E2eReport.check(suite, "right-clicking the TNT once the shield is broken picks it up ($round/$GOAL)", "SystemChat") {
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
        const val GOAL = 1
    }
}
