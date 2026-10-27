package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Capture Wool (Splat Zones; capture_seconds 10, wool-* zones, the count drops per second while one team owns every zone; the e2e image makes it
 * 5 per second): the red bot walks (A*) to the nearest unclaimed zone, stands alone in it until the capture message, then does
 * the same until it owns every zone (finishMatch waits for the count to reach 0).
 */
class CaptureWoolPlaythroughE2eTest : ObjectivePlaythroughBase() {

    private val suite = "Capture Wool playthrough"

    @Test
    fun `capture-wool is played until a team owns every zone and counts down`() {
        val ctx = startMatch(suite, "capture-wool", "Cw") ?: return
        val a = ctx.actor
        val others = listOf(ctx.idle)
        val remaining = ctx.objectives.zoneNames("wool-").toMutableList()
        val toWin = remaining.size // the count only moves while one team owns EVERY zone
        E2eReport.check(suite, "the stage has at least one wool zone", "content/stages") {
            assertThat(remaining.size).describedAs("wool zones: $remaining").isGreaterThanOrEqualTo(1)
            "zones=$remaining"
        }

        for (round in 1..toWin) {
            if (matchLines().isEmpty()) break
            val zone = remaining.minBy { ctx.zone(it).let { (zx, zz) -> Math.hypot(zx - a.x, zz - a.z) } }
            remaining.remove(zone)
            val (px, pz) = ctx.zone(zone)
            val walk = ctx.walker.walkTo(px, pz, 0.8, 120_000, others)
            walkCheck(ctx, "control point $zone", walk, px, pz)
            val captured = ctx.walker.hold(30_000, others) { ctx.chatCount("captured") >= round || matchLines().isEmpty() }
            E2eReport.check(suite, "standing alone in $zone for the capture time captures it ($round/$toWin)", "SystemChat / sidebar") {
                assertThat(captured).describedAs("no capture of $zone after 30s; pos=${posOf(a)} chat=${ctx.chat().takeLast(5)} sidebar=${sidebar(a)}").isTrue()
                "chat=${ctx.chat().filter { it.contains("captured") }}; sidebar=${sidebar(a)}"
            }
        }
        finishMatch(ctx)
    }
}
