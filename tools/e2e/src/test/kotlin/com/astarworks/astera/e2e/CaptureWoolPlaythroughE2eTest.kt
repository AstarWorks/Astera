package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Capture Wool (capture_seconds 10, three wool-* control points, a majority = 2 wins): the red bot walks (A*) to the nearest
 * unclaimed control point, stands alone in it until the capture message, then does the same for a second one.
 */
class CaptureWoolPlaythroughE2eTest : ObjectivePlaythroughBase() {

    private val suite = "Capture Wool playthrough"

    @Test
    fun `capture-wool is played until a team holds two control points`() {
        val ctx = startMatch(suite, "capture-wool", "Cw") ?: return
        val a = ctx.actor
        val others = listOf(ctx.idle)
        val remaining = ctx.objectives.zoneNames("wool-").toMutableList()
        E2eReport.check(suite, "the stage has at least three wool control points", "content/stages") {
            assertThat(remaining.size).describedAs("wool zones: $remaining").isGreaterThanOrEqualTo(3)
            "zones=$remaining"
        }

        for (round in 1..2) {
            if (matchLines().isEmpty()) break
            val zone = remaining.minBy { ctx.zone(it).let { (zx, zz) -> Math.hypot(zx - a.x, zz - a.z) } }
            remaining.remove(zone)
            val (px, pz) = ctx.zone(zone)
            val walk = ctx.walker.walkTo(px, pz, 1.2, 120_000, others)
            walkCheck(ctx, "control point $zone", walk, px, pz)
            val captured = ctx.walker.hold(30_000, others) { ctx.chatCount("captured") >= round || matchLines().isEmpty() }
            E2eReport.check(suite, "standing alone in $zone for the capture time captures it ($round/2)", "SystemChat / sidebar") {
                assertThat(captured).describedAs("no capture of $zone after 30s; pos=${posOf(a)} chat=${ctx.chat().takeLast(5)} sidebar=${sidebar(a)}").isTrue()
                "chat=${ctx.chat().filter { it.contains("captured") }}; sidebar=${sidebar(a)}"
            }
        }
        finishMatch(ctx)
    }
}
