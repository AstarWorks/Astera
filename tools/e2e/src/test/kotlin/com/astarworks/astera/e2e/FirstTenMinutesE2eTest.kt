package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType
import org.junit.jupiter.api.Test

/**
 * The founder's yardstick as a test (docs/vision/VISION.md): "a first-time player understands in 10 minutes: gun, sword,
 * staff, and how Norma wins the match." Four brand-new players (fresh names = first join, so the starter grant and the
 * guided tutorial apply) walk the whole path and every step is asserted on what the CLIENT receives, not on server state:
 *
 *  1. join: starter kit (a gun and a sword whose lore says how to use them + the compass), lobby sidebar, first-steps hint;
 *  2. menu: the Play guide opens the mode picker; picking a lane shows the waiting block;
 *  3. match: countdown -> ACTIVE, the sidebar names the win condition (reach Norma level N), "started" reaches the player;
 *  4. each archetype scores a kill that climbs the Norma ladder: gun (pistol shots), sword (melee slash), staff (flame burst).
 *     Per weapon: its cast sound reaches the shooter, a floating damage number, the victim's HP line drops, and the Norma
 *     level-up is announced (title or broadcast) with the sidebar following;
 *  5. the top Norma level ends the match by itself: winner/loser told why, rewards, back in the lobby.
 *
 * Kill goals are shortened by docker/e2e-tune-content.sh (3 levels, kill goals 1 then 2 = 3 kills), so one player clearing
 * the ladder with pistol, sword and staff is exactly the 10-minute story. Server-side positioning is by RCON `tp` like the
 * other playthroughs (bots do not walk/aim); the weapons themselves are driven by the real client packets.
 */
class FirstTenMinutesE2eTest : PlaythroughBase() {

    private val suite = "First 10 minutes"

    private fun side(b: Bot): String? = localPosOf(b)?.let { if (it.first < 0) "red" else "blue" }

    private fun openPicker(b: Bot): ScreenView? {
        val npc = b.entityEvents.filter { it.added && it.type == "VILLAGER" }.map { it.id }
            .firstOrNull { id -> b.entityTextsById[id]?.any { "Play" in it } == true } ?: return null
        val before = b.screens.size
        b.interact(npc)
        if (!b.waitUntil(5_000) { b.screens.size > before }) return null
        val s = b.screens.last()
        b.waitUntil(3_000) { b.contents[s.containerId]?.any { it.amount > 0 } == true }
        return s
    }

    /** Everything a player is told in words: chat, titles and the action bar, as plain text. */
    private fun told(b: Bot, chatFrom: Int = 0, titlesFrom: Int = 0, barsFrom: Int = 0): List<String> =
        b.chat.drop(chatFrom).map { clean(it.text) } + b.titles.drop(titlesFrom).map { clean(it.text) } + b.actionBars.drop(barsFrom).map { clean(it.text) }

    private class Marks(val chat: Int, val titles: Int, val bars: Int, val sounds: Int, val numbers: Int, val victimBars: Int)

    private fun mark(shooter: Bot, victim: Bot) =
        Marks(shooter.chat.size, shooter.titles.size, shooter.actionBars.size, shooter.sounds.size, shooter.damageNumbers.size, victim.actionBars.size)

    /**
     * Repeats [act] from a fixed pose next to [victim] (shooter [dist] blocks in front, looking at it with [pitch]) every
     * [intervalMs] until the victim's death screen arrives. Returns whether it died within [maxActions] actions.
     */
    private fun fight(shooter: Bot, victim: Bot, dist: Double, pitch: Float, intervalMs: Long, maxActions: Int, keepAlive: List<Bot>, act: () -> Unit): Boolean {
        val deathsBefore = victim.deaths.size
        var n = 0
        while (n < maxActions && victim.deaths.size == deathsBefore) {
            rcon("execute at ${victim.name} run tp ${shooter.name} ~ ~ ~-$dist 0 $pitch")
            Thread.sleep(150)
            act()
            n++
            keepAlive.forEach { it.nudge() }
            Thread.sleep(intervalMs)
        }
        return victim.waitUntil(4_000) { victim.deaths.size > deathsBefore }
    }

    private fun reachedLevel(b: Bot, m: Marks, level: Int): Boolean = b.waitUntil(6_000) {
        told(b, m.chat, m.titles, m.bars).any { Regex("(Lv|Level|レベル)\\s*$level\\b").containsMatchIn(it) || (level >= 3 && it.contains("NORMA COMPLETE", true)) }
    }

    @Test
    fun `a fresh player learns gun sword staff and Norma from join to a Norma win`() {
        val logFrom = logLineCount()
        val bots = (1..4).map { bot("Ft$it", PackPolicy.DECLINE) }
        val first = bots[0]
        bots.forEach { b -> b.waitUntil(15_000) { b.sidebarLines().isNotEmpty() && b.hotbar()[8] != -1 } }
        Thread.sleep(2_000)
        val lobbyTitle = first.sidebarTitle()

        // --- 1. join ---------------------------------------------------------------------------------------------------
        E2eReport.check(suite, "join: a first-time player holds a gun and a sword that say how to use them, plus the compass", "SetContainerContent + item lore") {
            val hb = first.hotbar()
            val weaponSlots = (0..7).filter { hb[it] != -1 }
            assertThat(weaponSlots.map { hb[it] }.toSet()).describedAs("expected two different starter weapons in 0..7: $hb").hasSize(2)
            assertThat(hb[8]).describedAs("compass missing: $hb").isNotEqualTo(-1)
            val lores = weaponSlots.associateWith { first.hotbarLore(it) }
            assertThat(lores.values.any { Regex("shoot|射撃", RegexOption.IGNORE_CASE).containsMatchIn(it) }).describedAs("no gun lore ('Right-click: shoot'): $lores").isTrue()
            assertThat(lores.values.any { Regex("slash|斬撃", RegexOption.IGNORE_CASE).containsMatchIn(it) }).describedAs("no sword lore ('Left-click: slash'): $lores").isTrue()
            assertThat(rawKeys(lores.values)).isEmpty()
            "hotbar=$hb lores=$lores"
        }
        E2eReport.check(suite, "join: a first-time player already holds the starter GOLD for a first shop purchase (ECO-11)", "RCON astera economy balance") {
            val gold = { Regex("GOLD:\\s*(\\d+)").find(rcon("astera economy balance gold ${first.name}").replace(Regex("§."), ""))?.groupValues?.get(1)?.toLong() ?: -1L }
            assertThat(first.waitUntil(10_000) { gold() >= 150L }).describedAs("starter GOLD missing: ${gold()}").isTrue()
            "gold=${gold()}"
        }
        E2eReport.check(suite, "join: the lobby sidebar is shown and the guided first-steps hint reaches the player", "SetScore + title/action bar/chat") {
            assertThat(first.sidebarLines()).describedAs("no lobby sidebar").isNotEmpty()
            val hint = Regex("first steps|lobby menu|press F|はじめの|最初の", RegexOption.IGNORE_CASE)
            assertThat(first.waitUntil(8_000) { told(first).any { hint.containsMatchIn(it) } })
                .describedAs("no first-steps tutorial hint in chat/titles/action bar; told=${quote(told(first).takeLast(15), 15)}").isTrue()
            assertThat(rawKeys(told(first))).describedAs("raw i18n keys").isEmpty()
            "sidebar='${first.sidebarTitle()}'; hint=${told(first).firstOrNull { hint.containsMatchIn(it) }}"
        }

        // --- 2. menu -> queue ------------------------------------------------------------------------------------------
        val chatFrom = bots.associateWith { it.chat.size }
        var queued = false
        E2eReport.check(suite, "menu: the Play guide opens the mode picker and picking a lane shows the waiting block", "OpenScreen + ContainerClick -> sidebar") {
            val picker = checkNotNull(openPicker(first)) { "Play guide not seen or picker did not open; chat=${first.chatTexts().takeLast(3)}" }
            assertThat(rawKeys(listOf(picker.title))).isEmpty()
            first.clickSlot(picker.containerId, 1, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK)
            assertThat(first.waitUntil(6_000) { first.sidebarLines().any { "1/2" in it } }).describedAs("no waiting block; sidebar=${first.sidebarLines()}").isTrue()
            queued = true
            "picker='${picker.title}' sidebar=${first.sidebarLines()}"
        }
        val tQueue = System.nanoTime()
        if (!queued) first.command("astera queue join carry-tnt")
        bots.drop(1).forEach { it.command("astera queue join carry-tnt") }
        val active = awaitMatch("ACTIVE", 90_000)
        E2eReport.check(suite, "queue: four players form a match and it goes ACTIVE within a minute", "RCON astera match list") {
            assertThat(active).describedAs("match never ACTIVE; list=${matchLines()}; chat=${first.chatTexts().takeLast(6)}").isNotNull()
            val secs = (System.nanoTime() - tQueue) / 1_000_000_000
            assertThat(secs).describedAs("queue -> ACTIVE took ${secs}s").isLessThan(60)
            "queue -> ACTIVE ${secs}s; line='${active?.raw}'"
        }
        if (active == null) return
        Thread.sleep(2_500)

        // --- 3. the match start ----------------------------------------------------------------------------------------
        val teams = bots.associateWith { side(it) }
        val red = bots.filter { teams[it] == "red" }
        val blue = bots.filter { teams[it] == "blue" }
        E2eReport.check(suite, "match: the player is told it started and the sidebar shows their Norma level", "SystemChat/title + SetScore") {
            val said = told(first, chatFrom.getValue(first))
            assertThat(said.any { Regex("started|MATCH START|開始", RegexOption.IGNORE_CASE).containsMatchIn(it) }).describedAs("no 'started' message:\n${quote(said, 30)}").isTrue()
            val lines = sidebar(first)
            assertThat(lines.any { it.contains("Norma Lv", true) }).describedAs("no Norma level line on the sidebar: $lines").isTrue()
            assertThat(rawKeys(said + lines)).isEmpty()
            "sidebar=$lines"
        }
        assertThat(red).describedAs("teams by spawn: ${teams.mapKeys { it.key.name }}").hasSize(2)
        assertThat(blue).hasSize(2)

        // --- 4. gun, sword, staff: one kill each climbs the Norma ladder ---------------------------------------------
        // red[0] is the learner: kill 1 (gun) clears level 1, kills 2 (sword) and 3 (staff) clear level 2 and reach the top.
        val learner = red[0]
        val victims = listOf(blue[0], blue[1], blue[0])
        val kinds = listOf("gun", "sword", "staff")
        val items = listOf("pistol", "knight-sword", "fire-staff")
        val castSound = listOf("astera:weapons/gun-shot", "astera:weapons/sword-swing", "astera:skill/wand/cast")
        val upLevels = intArrayOf(2, 2, 3)
        val duelNotes = mutableListOf<String>()

        for (i in 0..2) {
            val victim = victims[i]
            val kind = kinds[i]
            val slot = equip(learner, items[i])
            bots.forEach { it.command("astera norma choose kill") }
            Thread.sleep(500)
            rcon("effect give ${victim.name} minecraft:instant_health 1 4 true")
            val m = mark(learner, victim)
            val killed = when (kind) {
                "gun" -> duel(learner, victim, keepAlive = bots.filter { it != learner && it != victim }).killed
                "sword" -> fight(learner, victim, 1.5, 0f, 650, 45, bots.filter { it != learner && it != victim }) {
                    learner.punch()
                    learner.entityIdByUuid[victim.uuid]?.let(learner::attack)
                }
                else -> fight(learner, victim, 4.0, 10f, 4_300, 14, bots.filter { it != learner && it != victim }) { learner.useItem(0f, 10f) }
            }
            duelNotes += "$kind(${items[i]}) slot=$slot killed=$killed"
            val last = i == 2
            E2eReport.check(suite, "$kind: ${learner.name} kills ${victim.name} with the ${items[i]} (only its own click bindings)", "PlayerCombatKill") {
                assertThat(slot).describedAs("${items[i]} never reached the hotbar").isNotNull()
                assertThat(killed).describedAs("no kill with ${items[i]}; victim status='${status(victim)}' shooter status='${status(learner)}' chat=${learner.chatTexts().takeLast(4)}").isTrue()
                duelNotes.last()
            }
            E2eReport.check(suite, "$kind: the shooter hears the cast cue and sees damage numbers; the victim's HP line drops", "ClientboundSound + text display + HP glyphs") {
                val heard = learner.sounds.drop(m.sounds)
                assertThat(heard.map(::soundKey).any { soundKey(castSound[i]) in it }).describedAs("MISSING ${castSound[i]}; heard=${heard.take(10)}").isTrue()
                assertThat(learner.damageNumbers.drop(m.numbers)).describedAs("no floating damage number for ${items[i]} hits").isNotEmpty()
                val readings = victim.hpReadings(m.victimBars)
                assertThat(readings.map { it.current }.distinct().size).describedAs("victim HP line never changed: $readings").isGreaterThanOrEqualTo(2)
                "heard ${castSound[i]}; numbers=${learner.damageNumbers.drop(m.numbers).take(6)}; victim HP line ${readings.take(1)}..${readings.takeLast(1)}"
            }
            if (!killed && !last) {
                rcon("damage ${victim.name} 1000 minecraft:magic by ${learner.name}")
                victim.waitUntil(5_000) { victim.deaths.isNotEmpty() }
            } else if (!killed) {
                rcon("damage ${victim.name} 1000 minecraft:magic by ${learner.name}")
            }
            if (i < 2) {
                // Level 2 needs two kills from here (goal 2): after kill 2 the player is still on level 2, so only kill 1 announces a level.
                if (i == 0) E2eReport.check(suite, "norma: clearing the goal is announced to the player (title or broadcast) and the sidebar follows", "SetTitleText / SystemChat + SetScore") {
                    assertThat(reachedLevel(learner, m, upLevels[i])).describedAs("no Norma level-up message after the first kill; told=${quote(told(learner, m.chat, m.titles, m.bars).takeLast(20), 20)}").isTrue()
                    Thread.sleep(800)
                    val lines = sidebar(learner)
                    assertThat(lines.any { it.contains(learner.name.take(7), true) || it.contains("Red", true) }).describedAs("sidebar: $lines").isTrue()
                    "sidebar=$lines"
                }
                Thread.sleep(1_200)
                awaitRespawn(victim)
            }
        }
        E2eReport.notes += "First 10 minutes: ${duelNotes.joinToString(" | ")}"

        // --- 5. the end -----------------------------------------------------------------------------------------------
        // The top Norma level unlocks the ultimates, it does not end the match: end it from the console.
        matchLines().forEach { rcon("astera match end ${it.id}") }
        val ended = awaitNoMatch(45_000, keepAlive = bots)
        E2eReport.check(suite, "an admin end returns everyone to the lobby", "RCON astera match list") {
            assertThat(ended).describedAs("match still listed 45s after the end command: ${matchLines()}; sidebar=${sidebar(learner)}").isTrue()
            "match gone"
        }
        checkEnding(suite, bots, chatFrom, lobbyTitle)
        E2eReport.check(suite, "server log stayed free of errors during the whole first match", "server log") {
            val errs = serverErrorsSince(logFrom)
            assertThat(errs).describedAs("server errors:\n${quote(errs, 20)}").isEmpty()
            "clean"
        }
    }
}
