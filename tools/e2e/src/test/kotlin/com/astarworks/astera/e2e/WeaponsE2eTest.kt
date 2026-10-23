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
            // Heal after (re-)arrival: a heal given before the teleport is lost to a lobby respawn.
            rcon("effect give ${a.name} minecraft:instant_health 1 4 true")
            rcon("effect give ${b.name} minecraft:instant_health 1 4 true")
            Thread.sleep(500)
            if (ready(a, 0.5, 0.5) && ready(b, 0.5, 0.5 + distance)) {
                // Still alive and full after the window: no status is ticking the target down.
                Thread.sleep(700)
                if (ready(a, 0.5, 0.5) && ready(b, 0.5, 0.5 + distance)) return
            }
            if (attempt == 2) E2eReport.notes += "Weapons stage attempt ${attempt + 1} failed: a=${rcon("data get entity ${a.name} Dimension").trim().substringAfter("data: ")} hp=${hp(a)} pos=${pos(a)} gm=${rcon("data get entity ${a.name} playerGameType").trim().substringAfter("data: ")}; b=${rcon("data get entity ${b.name} Dimension").trim().substringAfter("data: ")} hp=${hp(b)} pos=${pos(b)}; chat a=${a.chatTexts().takeLast(3)} b=${b.chatTexts().takeLast(3)}"
        }
        E2eReport.notes += "Weapons stage gave up after 12 attempts: hp a=${hp(a)} b=${hp(b)} pos a=${pos(a)} b=${pos(b)}"
    }

    private fun pos(b: Bot): Triple<Double, Double, Double>? {
        val nums = Regex("(-?[0-9.]+)d").findAll(rcon("data get entity ${b.name} Pos")).map { it.groupValues[1].toDouble() }.toList()
        return if (nums.size == 3) Triple(nums[0], nums[1], nums[2]) else null
    }

    private fun survival(b: Bot): Boolean = rcon("data get entity ${b.name} playerGameType").trim().endsWith(" 0")

    /** Alive in the overworld, at full HP, in survival and standing within a block of the staged spot. */
    private fun ready(b: Bot, x: Double, z: Double): Boolean {
        if (!inOverworld(b)) return false
        val h = hp(b) ?: return false
        val p = pos(b) ?: return false
        return h >= 19.9 && survival(b) && Math.abs(p.first - x) < 1.0 && Math.abs(p.third - z) < 1.0
    }

    /** launch_speed (blocks per tick) of each ranged right-click skill, from content/skills. */
    private val launchSpeed = mapOf(
        "pistol" to 3.2, "rifle" to 4.5, "fire-staff" to 1.4, "frost-staff" to 2.2,
        "thunder-staff" to 3.0, "verdant-staff" to 2.4, "shotgun" to 20.0,
    )

    @Test
    fun `melee left click spends energy and ranged skills damage a still target`() {
        val a = bot("Shooter")
        val b = bot("Target")
        op(a)
        op(b)
        // The overworld arena is a night-time flat world: wandering zombies hit the bots, kill them (Astera
        // sends an out-of-match death to the lobby, where damage and hunger are off) and soak up skill arcs.
        // Keep it empty and bright. (Peaceful is not an option: vanilla refills food there and would move the bar.)
        for (rule in listOf("spawn_mobs", "doMobSpawning", "advance_time", "doDaylightCycle")) rcon("gamerule $rule false")
        rcon("time set day")
        rcon("execute in minecraft:overworld run kill @e[type=!minecraft:player]")
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
                    E2eReport.notes += "Weapons $item click #$n sent, victimId=${a.entityIdByUuid[b.uuid]} a.gm=${rcon("data get entity ${a.name} playerGameType").trim().substringAfter("data: ")}"
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
                // The projectile needs distance / speed to arrive; sample that long plus a margin for the cast
                // dispatch and the server-to-bot packet, instead of a fixed window.
                val windowMs = (4.0 / (launchSpeed[item] ?: 3.0) * 50.0).toLong() + 2_000
                repeat(tries) { n ->
                    stage(a, b, 4.0, 2_500)
                    val before = hp(b)
                    val chatBefore = b.chat.size
                    val histBefore = b.healthHistory.size
                    val logBefore = ServerLog.lines("weapons-debug").size
                    val startPos = "a=${pos(a)} b=${pos(b)} overworld a=${inOverworld(a)} b=${inOverworld(b)}"
                    val t0 = System.currentTimeMillis()
                    a.useItem(0f, 0f)
                    var after: Double? = null
                    var hitAtMs = -1L
                    var killed = false
                    while (System.currentTimeMillis() - t0 < windowMs) {
                        hp(b)?.let { v -> if (after == null || v < after!!) after = v }
                        // A lethal hit resets the victim to full HP in the lobby before a poll can see it: the
                        // "You were defeated" notice and the client's own SetHealth dip are evidence of that hit.
                        killed = b.chatTexts().drop(chatBefore).any { it.contains("defeated", ignoreCase = true) }
                        val dipped = before != null && (b.healthHistory.drop(histBefore).any { it < before } || (after ?: before) < before)
                        if (killed || dipped) { hitAtMs = System.currentTimeMillis() - t0; break }
                        Thread.sleep(50)
                    }
                    if (hitAtMs >= 0) hits++
                    val srv = ServerLog.lines("weapons-debug").drop(logBefore).filter { a.name in it || a.uuid.toString() in it }.map { it.substringAfter("[weapons-debug]").trim() }.takeLast(4)
                    trace += "try $n: hp $before -> $after hitAt=${hitAtMs}ms window=${windowMs}ms killed=$killed start[$startPos] server=$srv bar='${status(a)}'"
                }
                E2eReport.check(suite, "$item hits a still target $tries/$tries", "server Health") {
                    assertThat(hits).describedAs("hits=$hits/$tries; ${trace.joinToString(" | ")}").isEqualTo(tries)
                    "hits $hits/$tries (selected=$lastSelected); ${trace.joinToString(" | ") { it.substringBefore(" start[") }}"
                }
            }
        }
        E2eReport.notes += "weapons-debug trace: " + ServerLog.lines("weapons-debug").map { it.substringAfter("[weapons-debug]") }
        E2eReport.notes += "server log WARN/ERROR: " + ServerLog.lines("Astera").filter { "ERROR" in it || "WARN" in it }.takeLast(15)
    }
}
