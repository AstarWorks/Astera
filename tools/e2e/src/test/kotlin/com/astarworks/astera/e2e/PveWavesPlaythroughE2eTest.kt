package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * ADR-0249: a single player starts a real PvE run. One bot queues the `pve-waves` lane alone, the match starts after
 * the normal countdown, the waves appear as real entities (vanilla zombies / cave spiders carrying Astera's HP, damage
 * and names) that hunt the bot, and the bot clears them with its pistol - aimed from the mobs' server-side positions
 * (RCON `data get entity`), one right click per shot. The sidebar goal follows the waves ("Wave 2/3 - 5 enemies
 * left"), the last wave ends the match with a win, and the bot is paid and returned to the lobby.
 *
 * Fallback (documented, never silent): when the pistol has made no progress for [STALL_SHOTS] shots, the loop damages
 * the nearest mob over RCON so the rest of the run is still exercised. Using it fails the "kills by weapon" check.
 */
class PveWavesPlaythroughE2eTest : PlaythroughBase() {

    private val suite = "PvE waves playthrough"

    private data class Mob(val x: Double, val y: Double, val z: Double)

    private val waveLine = Regex("Wave\\s+(\\d+)/(\\d+).*?(\\d+)\\s+enemies left", RegexOption.IGNORE_CASE)

    /** `execute if entity` answers "Test passed" for exactly one match, "Test passed, count: N" otherwise. */
    private fun countOf(reply: String): Int {
        val text = clean(reply)
        return Regex("count: (\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: if (text.startsWith("Test passed")) 1 else 0
    }

    private fun mobCount(): Int = countOf(rcon("execute if entity @e[tag=astera_pve_mob]"))

    private fun mobCountNear(b: Bot, radius: Int): Int =
        countOf(rcon("execute at ${b.name} if entity @e[tag=astera_pve_mob,distance=..$radius]"))

    private fun nearestMob(b: Bot): Mob? {
        val reply = rcon("execute at ${b.name} run data get entity @e[tag=astera_pve_mob,sort=nearest,limit=1] Pos")
        return Regex("\\[(-?[0-9.]+)d, (-?[0-9.]+)d, (-?[0-9.]+)d]").find(reply)?.let {
            Mob(it.groupValues[1].toDouble(), it.groupValues[2].toDouble(), it.groupValues[3].toDouble())
        }
    }

    private fun distanceTo(b: Bot, m: Mob): Double? = posOf(b)?.let { hypot(hypot(it.first - m.x, it.second - m.y), it.third - m.z) }

    @Test
    fun `a solo player clears every wave of the PvE lane`() {
        val logFrom = logLineCount()
        val bot = bot("Pv", PackPolicy.DECLINE)
        bot.waitUntil(15_000) { bot.sidebarLines().isNotEmpty() && bot.hotbar()[8] != -1 }
        Thread.sleep(1_500)
        val lobbyTitle = bot.sidebarTitle()
        val chatFrom = mapOf(bot to bot.chat.size)

        val tQueue = System.nanoTime()
        bot.command("astera queue join pve-waves")
        val active = awaitMatch("ACTIVE", 80_000, keepAlive = listOf(bot))
        E2eReport.check(suite, "one player alone queues the PvE lane and the match goes ACTIVE (pacing)", "RCON astera match list") {
            assertThat(active).describedAs("never ACTIVE; list=${matchLines()}; chat=${bot.chatTexts().takeLast(6)}").isNotNull()
            "queue -> ACTIVE in ${(System.nanoTime() - tQueue) / 1_000_000_000}s; line='${active?.raw}'"
        }
        if (active == null) return

        // --- wave 1 appears as real entities ------------------------------------------------------------------
        val appeared = bot.waitUntil(20_000) { mobCount() > 0 }
        Thread.sleep(500)
        val sidebarFirst = sidebar(bot)
        E2eReport.check(suite, "the sidebar goal names the wave and the enemies left", "SetScore (sidebar)") {
            val line = sidebarFirst.firstOrNull { waveLine.containsMatchIn(it) }
            assertThat(line).describedAs("no 'Wave n/N - k enemies left' line: $sidebarFirst").isNotNull()
            assertThat(waveLine.find(line!!)!!.groupValues[2]).describedAs("wave total").isEqualTo("3")
            quote(sidebarFirst)
        }
        E2eReport.check(suite, "wave 1 exists as real mob entities near the player", "RCON execute if entity / data get entity") {
            assertThat(appeared).describedAs("no entity tagged astera_pve_mob after 20s; sidebar=$sidebarFirst log=${serverErrorsSince(logFrom)}").isTrue()
            val near = mobCountNear(bot, 45)
            assertThat(near).describedAs("mobs exist but none within 45 blocks of the player").isGreaterThan(0)
            val first = clean(rcon("execute at ${bot.name} run data get entity @e[tag=astera_pve_mob,sort=nearest,limit=1] Health"))
            val dmg = clean(rcon("execute at ${bot.name} as @e[tag=astera_pve_mob,sort=nearest,limit=1] run attribute @s minecraft:attack_damage get"))
            val nameData = clean(rcon("execute at ${bot.name} run data get entity @e[tag=astera_pve_mob,sort=nearest,limit=1] CustomName"))
            val kinds = clean(rcon("execute at ${bot.name} run data get entity @e[tag=astera_pve_mob,sort=nearest,limit=1] id"))
            assertThat(first).describedAs("Astera max HP 18 (dark-zombie)").containsAnyOf("18.0f", "18.0")
            assertThat(dmg).describedAs("Astera melee damage 3.5").contains("3.5")
            assertThat(nameData).describedAs("Astera display name").contains("Dark Zombie")
            val clientSaw = bot.spawnedEntityTypes.keys.filter { it.contains("ZOMBIE", true) || it.contains("SPIDER", true) }
            assertThat(clientSaw).describedAs("the bot client never received a zombie / spider spawn: ${bot.spawnedEntityTypes.keys}").isNotEmpty()
            "mobs=${mobCount()} near=$near id=${kinds.substringAfter("data: ")} health=${first.substringAfter("data: ")} damage=${dmg.trim()} name=${nameData.substringAfter("data: ").take(80)} client saw $clientSaw"
        }

        // --- they hunt the player ---------------------------------------------------------------------------------
        val m0 = nearestMob(bot)
        val d0 = m0?.let { distanceTo(bot, it) }
        val hp0 = bot.health
        Thread.sleep(1_500)
        val m1 = nearestMob(bot)
        val d1 = m1?.let { distanceTo(bot, it) }
        E2eReport.check(suite, "the mobs walk towards the player (they target it)", "RCON data get entity Pos") {
            assertThat(d0).describedAs("no mob position").isNotNull()
            assertThat(d1 != null && d0 != null && (d1 < d0 - 2.0 || d1 < 3.0 || bot.health < hp0))
                .describedAs("nearest mob distance $d0 -> $d1, bot health $hp0 -> ${bot.health}").isTrue()
            "nearest mob %.1f -> %.1f blocks in 1.5s; bot health %.1f -> %.1f".format(d0, d1, hp0, bot.health)
        }

        // --- clear the waves with the pistol ---------------------------------------------------------------------
        val slot = equip(bot, "pistol")
        E2eReport.check(suite, "the bot holds a pistol", "SetContainerSlot") {
            assertThat(slot).describedAs("pistol never arrived").isNotNull()
            "hotbar slot $slot"
        }
        val wavesSeen = sortedSetOf<Int>()
        var maxLeftSeen = 0
        var shots = 0
        var sinceReload = 0
        var stalled = 0
        var lastCount = mobCount()
        var fallbackUsed = 0
        val deadline = System.currentTimeMillis() + 240_000
        while (matchLines().isNotEmpty() && System.currentTimeMillis() < deadline) {
            sidebar(bot).firstNotNullOfOrNull { waveLine.find(it) }?.let {
                wavesSeen += it.groupValues[1].toInt()
                maxLeftSeen = maxOf(maxLeftSeen, it.groupValues[3].toInt())
            }
            val mob = nearestMob(bot)
            if (mob == null) {
                bot.nudge()
                Thread.sleep(700)
                continue
            }
            val me = posOf(bot) ?: continue
            val dx = mob.x - me.first
            val dz = mob.z - me.third
            val dy = (mob.y + 0.9) - (me.second + 1.62)
            val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
            val pitch = (-Math.toDegrees(atan2(dy, hypot(dx, dz)))).toFloat()
            rcon("rotate ${bot.name} $yaw $pitch")
            val ammo = ammoOf(bot)
            if ((ammo != null && ammo <= 0) || sinceReload >= 13) {
                reload(bot)
                sinceReload = 0
                continue
            }
            bot.useItem(yaw, pitch)
            shots++
            sinceReload++
            Thread.sleep(330)

            val count = mobCount()
            if (count < lastCount) stalled = 0 else stalled++
            lastCount = count
            if (stalled >= STALL_SHOTS) {
                // Documented fallback: the pistol made no progress; damage the nearest mob so the run continues.
                rcon("execute at ${bot.name} as @e[tag=astera_pve_mob,sort=nearest,limit=1] run damage @s 100 minecraft:generic by ${bot.name}")
                fallbackUsed++
                stalled = 0
            }
        }

        E2eReport.check(suite, "the sidebar counted through the waves (1, 2 and 3 were shown)", "SetScore (sidebar)") {
            assertThat(wavesSeen).describedAs("waves seen on the sidebar").containsExactly(1, 2, 3)
            "waves seen=$wavesSeen, most enemies shown at once=$maxLeftSeen"
        }
        E2eReport.check(suite, "every mob died to the bot's pistol (aim + clicks, no RCON damage)", "RCON + SetEntityMotion/UseItem") {
            assertThat(fallbackUsed).describedAs("pistol stalled; RCON damage fallback was needed $fallbackUsed time(s), shots=$shots").isZero()
            assertThat(shots).describedAs("no shot fired").isGreaterThan(0)
            "$shots shots, fallback uses=$fallbackUsed, bot health ${bot.health}, deaths=${bot.deaths}"
        }

        val ended = awaitNoMatch(40_000, keepAlive = listOf(bot))
        E2eReport.check(suite, "clearing the last wave ends the match by itself", "RCON astera match list") {
            assertThat(ended).describedAs("match still listed: ${matchLines()}; sidebar=${sidebar(bot)}; deaths=${bot.deaths}").isTrue()
            "match gone"
        }
        val chat = bot.chat.drop(chatFrom.getValue(bot)).map { clean(it.text) }
        E2eReport.check(suite, "the result says the encounter was cleared (win), with kills counted", "SystemChat") {
            assertThat(chat.any { it.contains("All 3 waves", true) }).describedAs("no 'All 3 waves cleared' line:\n${quote(chat, 60)}").isTrue()
            assertThat(chat.any { it.contains("waves of the encounter were cleared", true) }).describedAs("no win reason:\n${quote(chat, 60)}").isTrue()
            val you = chat.firstOrNull { it.startsWith("You:") }.orEmpty()
            assertThat(Regex("(\\d+) kills").find(you)?.groupValues?.get(1)?.toInt() ?: 0).describedAs("own line '$you'").isGreaterThanOrEqualTo(8)
            quote(chat.filter { it.contains("wave", true) || it.startsWith("You:") || it.contains("Summary") }, 12)
        }
        checkEnding(suite, listOf(bot), chatFrom, lobbyTitle)
        E2eReport.check(suite, "no PvE mob is left in the world after the match", "RCON execute if entity") {
            assertThat(mobCount()).describedAs("mobs left after the match").isZero()
            "none left"
        }
        E2eReport.check(suite, "server log stayed free of errors during the match", "server log") {
            val errs = serverErrorsSince(logFrom)
            assertThat(errs).describedAs("server errors:\n${quote(errs, 20)}").isEmpty()
            "clean"
        }
    }

    private companion object {
        /** Shots in a row without the live-mob count dropping before the RCON fallback kicks in. */
        const val STALL_SHOTS = 45
    }
}
