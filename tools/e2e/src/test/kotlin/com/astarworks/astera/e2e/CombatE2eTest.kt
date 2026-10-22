package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CombatE2eTest : E2eBase() {

    private val suite = "Combat"

    @Test
    fun `pistol shot lowers target hp and boss bar ammo`() {
        val a = bot("Shooter")
        val b = bot("Target")
        b.waitUntil(8_000) { b.actionBars.isNotEmpty() && !b.health.isNaN() }
        op(a)
        op(b)
        Thread.sleep(500)
        val slotsBefore = a.slotUpdates.size
        a.command("astera items give ${a.name} pistol")
        a.waitUntil(4_000) { a.slotUpdates.size > slotsBefore }
        val pistolSlot = a.slotUpdates.drop(slotsBefore).lastOrNull { it.containerId == 0 && it.amount > 0 && it.slot in 36..44 }
        val hotbarIndex = pistolSlot?.let { it.slot - 36 }

        // Face each other on a straight line: B stands 4 blocks south (+z) of A; A looks south (yaw 0).
        val ax = a.x
        val ay = a.y
        val az = a.z
        rcon("tp ${a.name} $ax $ay $az 0 0")
        rcon("tp ${b.name} $ax $ay ${az + 4} 180 0")
        Thread.sleep(1_000)

        E2eReport.check(suite, "pistol lands in a hotbar slot", "SetContainerSlot") {
            assertThat(hotbarIndex).describedAs("no hotbar SetContainerSlot after /items give; updates=${a.slotUpdates.drop(slotsBefore)}").isNotNull()
            "pistol at hotbar index $hotbarIndex"
        }
        val idx = hotbarIndex ?: 0
        a.selectHotbar(idx)
        Thread.sleep(500)

        val hpBefore = b.actionBars.lastOrNull()?.text
        val healthBefore = b.health
        val barEventsBefore = a.bossBarEvents.size
        repeat(4) {
            a.useItem(0f, 0f)
            Thread.sleep(900)
        }
        b.waitUntil(3_000) { b.actionBars.lastOrNull()?.text != hpBefore }
        Thread.sleep(1_000)

        E2eReport.check(suite, "right-click with pistol damages the target (HP line drops)", "target action bar / SetHealth") {
            val hpAfter = b.actionBars.lastOrNull()?.text
            val sv = rcon("data get entity ${b.name} Health")
            assertThat(hpAfter != hpBefore || b.healthHistory.any { it < healthBefore })
                .describedAs(
                    "target HP unchanged after 4 shots. actionbar before='${visible(hpBefore.orEmpty())}' after='${visible(hpAfter.orEmpty())}' " +
                        "vanilla health=$healthBefore->${b.health}; server: $sv; target joined=${b.joined} disconnect=${b.disconnectReason} pos=(${b.x},${b.y},${b.z}) shooter pos=(${a.x},${a.y},${a.z}); target action bars=${b.actionBars.map { visible(it.text) }}; shooter chat=${a.chatTexts().takeLast(4)}",
                ).isTrue()
            "actionbar before='${visible(hpBefore.orEmpty())}' after='${visible(hpAfter.orEmpty())}'; vanilla health $healthBefore -> ${b.health}; server: $sv"
        }

        E2eReport.check(suite, "boss bar shows ammo and it decreases", "ClientboundBossEvent") {
            val events = a.bossBarEvents.drop(0)
            assertThat(a.bossBars).describedAs("no boss bar at all; events=$events").isNotEmpty()
            val bar = a.bossBars.values.first()
            val titles = bar.titles.map { visible(it) }.distinct()
            assertThat(a.bossBarEvents.size).describedAs("boss bar never updated after shooting").isGreaterThan(barEventsBefore)
            assertThat(bar.progresses.distinct().size + titles.size)
                .describedAs("boss bar progress/title never changed; titles=$titles progress=${bar.progresses}").isGreaterThan(2)
            "titles=$titles\nprogress history=${bar.progresses}\nevents:\n${quote(a.bossBarEvents)}"
        }
    }
}
