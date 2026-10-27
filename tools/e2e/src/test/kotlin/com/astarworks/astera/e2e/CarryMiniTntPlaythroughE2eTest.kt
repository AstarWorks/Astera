package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Carry Mini TNT (Clam Blitz; content/game-modes/carry-mini-tnt.yaml: clams on the stage's clam-<n> spots, up to 8 carried, ten make a
 * power TNT, only a thrown-in power TNT opens an enemy basket barrier). One bot cannot make a power TNT (the carry limit is 8 and the
 * idle bot is the other team), so this walks laps over the clam spots until 8 are in the bag and lets the clock end the match; the
 * power TNT + barrier rules are covered by the game-sim scenario in SiegeModesScenarioTest.
 */
class CarryMiniTntPlaythroughE2eTest : ObjectivePlaythroughBase() {

    private val suite = "Carry Mini TNT playthrough"

    @Test
    fun `carry-mini-tnt is played from collecting clams to the time limit`() {
        val ctx = startMatch(suite, "carry-mini-tnt", "Mt") ?: return
        val spots = ctx.objectives.zoneNames("clam-").sorted().map { ctx.zone(it) }
        val a = ctx.actor
        val others = listOf(ctx.idle)
        E2eReport.check(suite, "the stage has clam spots and an enemy basket", "content/stages") {
            assertThat(spots).describedAs("clam-<n> zones").isNotEmpty()
            assertThat(ctx.objectives.zoneNames("basket-")).describedAs("basket-<team> zones").isNotEmpty()
            "clams=${spots.size}"
        }

        var picked = 0
        for ((sx, sz) in spots) {
            if (picked >= CARRY_LIMIT || matchLines().isEmpty()) break
            ctx.walker.walkTo(sx, sz, 1.2, 120_000, others)
            // A pickup is a short hold: stay on the spot until the clam is in the bag.
            if (a.waitUntil(3_000) { ctx.chatCount("/$CARRY_LIMIT.") > picked }) picked++
        }
        E2eReport.check(suite, "standing on clam spots picks clams up (carried/limit on chat)", "SystemChat / sidebar") {
            assertThat(ctx.chatCount("/$CARRY_LIMIT.")).describedAs("no clam picked up; pos=${posOf(a)} chat=${ctx.chat().takeLast(6)}").isGreaterThanOrEqualTo(3)
            "picked=$picked; sidebar=${sidebar(a)}"
        }
        finishMatch(ctx, endTimeoutMs = 240_000, actorWins = false)
    }

    private companion object {
        const val CARRY_LIMIT = 8
    }
}
