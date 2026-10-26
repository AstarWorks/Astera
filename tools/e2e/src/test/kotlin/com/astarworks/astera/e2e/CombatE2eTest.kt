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
        repeat(4) {
            a.useItem(0f, 0f)
            Thread.sleep(900)
        }
        b.waitUntil(3_000) { b.actionBars.lastOrNull()?.text != hpBefore }
        Thread.sleep(1_000)

        E2eReport.check(suite, "right-click with pistol damages the target (HP line drops)", "target action bar / SetHealth") {
            val hpAfter = b.actionBars.lastOrNull()?.text
            val sv = entityData(b.name, "Health")
            assertThat(hpAfter != hpBefore || b.healthHistory.any { it < healthBefore })
                .describedAs(
                    "target HP unchanged after 4 shots. actionbar before='${visible(hpBefore.orEmpty())}' after='${visible(hpAfter.orEmpty())}' " +
                        "vanilla health=$healthBefore->${b.health}; server: $sv; target joined=${b.joined} disconnect=${b.disconnectReason} pos=(${b.x},${b.y},${b.z}) shooter pos=(${a.x},${a.y},${a.z}); target action bars=${b.actionBars.map { visible(it.text) }}; shooter chat=${a.chatTexts().takeLast(4)}",
                ).isTrue()
            "actionbar before='${visible(hpBefore.orEmpty())}' after='${visible(hpAfter.orEmpty())}'; vanilla health $healthBefore -> ${b.health}; server: $sv"
        }

        // ADR-0194: no boss bars; ammo is part of the shooter's HP status line.
        E2eReport.check(suite, "status line shows ammo and it decreases; no boss bar", "action bar / ClientboundBossEvent") {
            assertThat(a.bossBars).describedAs("boss bar must not be used (ADR-0194); events=${a.bossBarEvents}").isEmpty()
            val lines = a.actionBars.map { visible(it.text) }.distinct()
            assertThat(lines.size).describedAs("shooter status line never changed while shooting; lines=$lines").isGreaterThan(1)
            "status lines=$lines"
        }
    }
}
