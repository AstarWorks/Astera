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

    private fun status(b: Bot): String = b.actionBars.lastOrNull()?.text.orEmpty().filter { it.code < 0xE000 }.take(80)

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

    private fun inOverworld(b: Bot): Boolean = "overworld" in rcon("data get entity ${b.name} Dimension")

    /**
     * Puts both bots in the overworld, alive and settled. Out-of-match Astera deaths (lingering status damage
     * from the previous shot) send the victim back to the lobby, where damage and hunger are disabled by
     * design; a shot taken from/at the lobby says nothing about the weapon, so re-stage until both bots stay
     * in the overworld across a settle window.
     */
    private fun stage(a: Bot, b: Bot, distance: Double, settleMs: Long) {
        repeat(12) { attempt ->
            face(a, b, distance)
            Thread.sleep(settleMs)
            if (inOverworld(a) && inOverworld(b)) {
                Thread.sleep(700)
                if (inOverworld(a) && inOverworld(b)) return
            }
            if (attempt == 2) E2eReport.notes += "Weapons stage attempt ${attempt + 1} failed: a=${rcon("data get entity ${a.name} Dimension").trim().substringAfter("data: ")} hp=${hp(a)} gm=${rcon("data get entity ${a.name} playerGameType").trim().substringAfter("data: ")}; b=${rcon("data get entity ${b.name} Dimension").trim().substringAfter("data: ")} hp=${hp(b)}; chat a=${a.chatTexts().takeLast(3)} b=${b.chatTexts().takeLast(3)}"
        }
    }

    /** Lowest target HP seen while polling: a killing blow resets the HP mirror on respawn, so sample during. */
    private fun lowestHp(b: Bot, windowMs: Long): Double? {
        var min: Double? = null
        val end = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < end) {
            hp(b)?.let { v -> if (min == null || v < min!!) min = v }
            Thread.sleep(50)
        }
        return min
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
                    rcon("effect give ${b.name} minecraft:instant_health 1 4 true")
                    stage(a, b, 2.2, 1_500)
                    val before = food(a)
                    // A real client sends the swing plus the attack on the entity in its crosshair.
                    E2eReport.notes += "Weapons $item click #$n sent, a.gm=${rcon("data get entity ${a.name} playerGameType").trim().substringAfter("data: ")}"
                    a.punch()
                    a.entityIdByUuid[b.uuid]?.let(a::attack)
                    // Sample through the swing: the cost is charged on cast, and energy regenerates right after.
                    val samples = mutableListOf<Int>()
                    val sampleEnd = System.currentTimeMillis() + 1_200
                    while (System.currentTimeMillis() < sampleEnd) { food(a)?.let(samples::add); Thread.sleep(60) }
                    val after = samples.minOrNull()
                    E2eReport.check(suite, "$item left click #$n spends energy (food bar)", "server foodLevel") {
                        assertThat(after != null && before != null && after < before)
                            .describedAs("food $before -> $samples; hp target=${hp(b)}; pos a=${rcon("data get entity ${a.name} Pos").trim()} b=${rcon("data get entity ${b.name} Pos").trim()}; dim=${rcon("data get entity ${a.name} Dimension").trim()}; selected=$lastSelected; server=${ServerLog.lines("weapons-debug").filter { a.name in it || a.uuid.toString() in it }.takeLast(6).map { it.substringAfter("[weapons-debug]") }}").isTrue()
                        "food $before -> min $after"
                    }
                }
            } else {
                var hits = 0
                val trace = mutableListOf<String>()
                val tries = 4
                repeat(tries) {
                    rcon("effect give ${a.name} minecraft:instant_health 1 4 true")
                    rcon("effect give ${b.name} minecraft:instant_health 1 4 true")
                    stage(a, b, 4.0, 2_500)
                    val before = hp(b)
                    val chatBefore = b.chat.size
                    a.useItem(0f, 0f)
                    val after = lowestHp(b, 1_800)
                    // A lethal hit resets the victim to full HP in the lobby before the poll can see it: the
                    // "You were defeated" notice is the evidence of that hit.
                    val killed = b.chatTexts().drop(chatBefore).any { it.contains("defeated", ignoreCase = true) }
                    if (killed || (before != null && after != null && after < before)) hits++
                    trace += "try: hp $before -> $after; a=${rcon("data get entity ${a.name} Pos").trim().substringAfter("data: ")} b=${rcon("data get entity ${b.name} Pos").trim().substringAfter("data: ")} dim=${rcon("data get entity ${b.name} Dimension").trim().substringAfterLast(" ")}; bar='${status(a)}'"
                }
                E2eReport.check(suite, "$item hits a still target $tries/$tries", "server Health") {
                    assertThat(hits).describedAs("hits=$hits/$tries; ${trace.joinToString(" | ")}").isEqualTo(tries)
                    "hits $hits/$tries (selected=$lastSelected)"
                }
            }
        }
        E2eReport.notes += "weapons-debug trace: " + ServerLog.lines("weapons-debug").map { it.substringAfter("[weapons-debug]") }
        E2eReport.notes += "server log WARN/ERROR: " + ServerLog.lines("Astera").filter { "ERROR" in it || "WARN" in it }.takeLast(15)
    }
}
