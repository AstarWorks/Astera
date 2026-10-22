package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Outside a match (no teams): melee left clicks spend energy (food bar) and ranged right-click skills damage a
 * motionless target. Regression for the live-bot findings (melee spent nothing, ranged hits were flaky).
 */
class WeaponsE2eTest : E2eBase() {

    private val suite = "Weapons"

    private fun hp(b: Bot): Double? =
        Regex("(-?[0-9.]+)f?$").find(rcon("data get entity ${b.name} Health").trim())?.groupValues?.get(1)?.toDoubleOrNull()

    private fun food(b: Bot): Int? =
        rcon("data get entity ${b.name} foodLevel").trim().substringAfterLast(" ").toIntOrNull()

    private fun give(a: Bot, item: String): Int? {
        // One weapon at a time: the lobby kit leaves only a few free hotbar slots, so a second give would
        // land in the main inventory and never be selectable.
        rcon("clear ${a.name}")
        Thread.sleep(300)
        val before = a.slotUpdates.size
        a.command("astera items give ${a.name} $item")
        a.waitUntil(4_000) { a.slotUpdates.size > before }
        Thread.sleep(300)
        // Only updates caused by this give: older ones point at the previous weapons' slots.
        val slot = a.slotUpdates.drop(before).lastOrNull { it.containerId == 0 && it.amount > 0 && it.slot in 36..44 }?.slot?.minus(36)
        if (slot != null) {
            a.selectHotbar(slot)
            Thread.sleep(400)
        }
        lastSelected = rcon("data get entity ${a.name} SelectedItem").trim()
        return slot
    }

    private var lastSelected: String = ""

    private fun face(a: Bot, b: Bot, distance: Double) {
        val ax = 0.5
        val az = 0.5
        // The lobby world cancels damage; fight in the overworld like a match backend does.
        rcon("execute in minecraft:overworld run tp ${a.name} $ax -60 $az 0 0")
        rcon("execute in minecraft:overworld run tp ${b.name} $ax -60 ${az + distance} 180 0")
        Thread.sleep(800)
    }

    @Test
    fun `melee left click spends energy and ranged skills damage a still target`() {
        val a = bot("Shooter")
        val b = bot("Target")
        op(a)
        op(b)
        rcon("gamemode survival ${a.name}")
        rcon("gamemode survival ${b.name}")
        Thread.sleep(1_000)

        for ((item, melee) in listOf(
            "dagger" to true, "knight-sword" to true, "greatsword" to true,
            "pistol" to false, "rifle" to false, "fire-staff" to false, "frost-staff" to false,
            "thunder-staff" to false, "verdant-staff" to false, "shotgun" to false,
        )) {
            val slot = give(a, item)
            E2eReport.check(suite, "$item lands in a selectable hotbar slot", "SetContainerSlot") {
                assertThat(slot).describedAs("no hotbar slot update after /items give $item").isNotNull()
                "hotbar $slot; selected=$lastSelected"
            }
            if (slot == null) continue
            if (melee) {
                repeat(3) { n ->
                    rcon("effect give ${a.name} minecraft:instant_health 1 4 true")
                    face(a, b, 2.2)
                    Thread.sleep(1_500)
                    val before = food(a)
                    a.punch()
                    Thread.sleep(300)
                    val after = food(a)
                    E2eReport.check(suite, "$item left click #$n spends energy (food bar)", "server foodLevel") {
                        assertThat(after != null && before != null && after < before)
                            .describedAs("food $before -> $after; hp target=${hp(b)}; selected=$lastSelected").isTrue()
                        "food $before -> $after"
                    }
                }
            } else {
                var hits = 0
                val tries = 4
                repeat(tries) {
                    rcon("effect give ${b.name} minecraft:instant_health 1 4 true")
                    face(a, b, 4.0)
                    Thread.sleep(2_500)
                    val before = hp(b)
                    a.useItem(0f, 0f)
                    Thread.sleep(1_800)
                    val after = hp(b)
                    if (before != null && after != null && after < before) hits++
                }
                E2eReport.check(suite, "$item hits a still target $tries/$tries", "server Health") {
                    assertThat(hits).describedAs("hits=$hits/$tries").isEqualTo(tries)
                    "hits $hits/$tries (selected=$lastSelected)"
                }
            }
        }
        E2eReport.notes += "server log WARN/ERROR: " + ServerLog.lines("Astera").filter { "ERROR" in it || "WARN" in it }.takeLast(15)
    }
}
