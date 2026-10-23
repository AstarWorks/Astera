package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType
import org.junit.jupiter.api.Test

/**
 * In-match stars and Norma levels (docs/game-design/stars-and-norma.md, ADR-0251): a star is an item of the match,
 * never carried out of it.
 *
 * Two bots play a Norma match with the e2e-tuned content (docker/e2e-tune-content.sh): generators pop every 3 s,
 * max Norma level 3, star goals 2 then 3, kill goals 1 then 2. Asserted: the match sidebar shows 0 stars, Lv 1/3 and
 * the goal at the start; a bot standing on a generator picks the popped stars up and levels up through the STAR goal;
 * a bot far from every generator stays at 0; a hit changes nothing; a kill bursts the victim's stars (victim 0, the
 * other bot walks over the drops) and the KILL goal levels the killer up; reaching the top level ends the match; and
 * afterwards the lobby sidebar has no star line, the wallet lists no STAR and no chat line mentions stars.
 */
class StarsE2eTest : PlaythroughBase() {

    private val suite = "Stars"

    /** The carried-stars number on the sidebar (`* 3 Stars`), or null when there is no such line. */
    private fun starCount(b: Bot): Int? =
        sidebar(b).firstNotNullOfOrNull { Regex("(\\d+)\\s*Stars\\b", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** `Lv 2/3` on the sidebar as (level, max), or null. */
    private fun levelOf(b: Bot): Pair<Int, Int>? =
        sidebar(b).firstNotNullOfOrNull { Regex("Lv\\s*(\\d+)/(\\d+)").find(it)?.let { m -> m.groupValues[1].toInt() to m.groupValues[2].toInt() } }

    /** Dropped-item entities (a star drop is a glowing NETHER_STAR item) this bot currently sees within [r] blocks of (x, z). */
    private fun itemsNear(b: Bot, x: Double, z: Double, r: Double): Int =
        b.entities.values.count { it.type.equals("ITEM", ignoreCase = true) && Math.hypot(it.x - x, it.z - z) <= r }

    @Test
    fun `stars pop on the map, burst on a kill, level the norma up and nothing survives the match`() {
        val a = bot("Sa", PackPolicy.DECLINE)
        val c = bot("Sb", PackPolicy.DECLINE)
        val both = listOf(a, c)
        both.forEach { b -> b.waitUntil(15_000) { b.sidebarLines().isNotEmpty() && b.hotbar()[8] != -1 } }
        Thread.sleep(1_500)
        val lobbyTitle = a.sidebarTitle()
        val chatFrom = both.associateWith { it.chat.size }
        both.forEach { it.command("astera queue join norma") }

        val active = awaitMatch("ACTIVE", 60_000, keepAlive = both)
        E2eReport.check(suite, "a two-player Norma match goes ACTIVE", "RCON astera match list") {
            assertThat(active).describedAs("match never ACTIVE; chat=${a.chatTexts().takeLast(6)}").isNotNull()
            "line='${active?.raw}'"
        }
        if (active == null) return
        Thread.sleep(2_500) // generators have popped at least once; nobody stands on a pedestal

        E2eReport.check(suite, "the match sidebar shows 0 stars, Lv 1/3 and the goal (Stars 0/2) for everyone at the start", "SetScore") {
            for (b in both) {
                assertThat(starCount(b)).describedAs("${b.name} sidebar ${sidebar(b)}").isEqualTo(0)
                assertThat(levelOf(b)).describedAs("${b.name} sidebar ${sidebar(b)}").isEqualTo(1 to 3)
                assertThat(sidebar(b).any { Regex("Goal:\\s*Stars\\s*0/2").containsMatchIn(it) }).describedAs("no star goal line: ${sidebar(b)}").isTrue()
                assertThat(rawKeys(sidebar(b))).isEmpty()
            }
            quote(sidebar(a))
        }

        // Norma choice without commands: the picker window opens by itself (start of the match), a click picks the goal.
        val windows = both.associateWith { b -> b.waitUntil(10_000) { b.screens.any { it.title.contains("Norma goal") } } }
        E2eReport.check(suite, "a Norma goal picker window opens for every player at match start", "ClientboundOpenScreen") {
            for (b in both) {
                assertThat(windows.getValue(b)).describedAs("${b.name}: no picker; screens=${b.screens.map { it.title }}; chat=${b.chatTexts().takeLast(3)}").isTrue()
                val sc = b.screens.last { it.title.contains("Norma goal") }
                assertThat(rawKeys(listOf(sc.title))).isEmpty()
                assertThat(b.waitUntil(3_000) { b.contents[sc.containerId]?.any { it.amount > 0 } == true }).describedAs("picker window is empty").isTrue()
            }
            "titles=${both.map { b -> b.screens.last { it.title.contains("Norma goal") }.title }}"
        }
        E2eReport.check(suite, "clicking the KILL tile (slot 6) switches the goal to Kills 0/1; clicking STAR (slot 2) keeps Stars 0/2", "ServerboundContainerClick -> SetScore") {
            val sc = c.screens.last { it.title.contains("Norma goal") }
            val before = c.chat.size
            c.clickSlot(sc.containerId, 6, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK)
            assertThat(c.waitUntil(6_000) { sidebar(c).any { Regex("Goal:\\s*Kills\\s*0/1").containsMatchIn(it) } })
                .describedAs("C goal did not become Kills 0/1: ${sidebar(c)}; chat=${c.chatSince(before).map { clean(it) }}").isTrue()
            val sa = a.screens.last { it.title.contains("Norma goal") }
            a.clickSlot(sa.containerId, 2, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK)
            Thread.sleep(800)
            assertThat(sidebar(a).any { Regex("Goal:\\s*Stars\\s*0/2").containsMatchIn(it) }).describedAs("A sidebar ${sidebar(a)}").isTrue()
            "C: ${sidebar(c)}; A: ${sidebar(a)}"
        }
        // The window closes by itself after 10 s; /astera norma choose keeps working (C goes back to STAR for the pedestal run).
        val cmdFrom = c.chat.size
        c.command("astera norma choose star")
        E2eReport.check(suite, "/astera norma choose star still works", "SystemChat") {
            val reply = c.chatSince(cmdFrom).map { clean(it) }.joinToString(" ")
            assertThat(reply).describedAs("no reply").containsIgnoringCase("star")
            assertThat(c.waitUntil(4_000) { sidebar(c).any { Regex("Goal:\\s*Stars").containsMatchIn(it) } }).describedAs("C sidebar ${sidebar(c)}").isTrue()
            reply
        }

        // (generator star is a visible entity) A stands 5 blocks off the east generator and must see the dropped star there.
        tpArena(a.name, 17.5, 65.0, 0.5, 0f, 0f)
        val seen = a.waitUntil(10_000) { a.nudge(); c.nudge(); itemsNear(a, 12.5, 0.5, 2.5) >= 1 }
        E2eReport.check(suite, "a generator star appears in the world as an item entity at the generator", "ClientboundAddEntity(ITEM)") {
            assertThat(seen).describedAs("no ITEM entity near (12.5, 0.5); entities=${a.entities.values.map { it.type }.groupingBy { it }.eachCount()}").isTrue()
            "items near generator: ${itemsNear(a, 12.5, 0.5, 2.5)}"
        }

        // (a) + STAR-goal level-up: A stands on the east generator (12.5, 65, 0.5) and takes what pops; 2 stars clear level 1.
        val spawnA = posOf(a)
        tpArena(a.name, 12.5, 65.0, 0.5, 0f, 0f)
        val leveled = a.waitUntil(40_000) { a.nudge(); c.nudge(); levelOf(a)?.first == 2 }
        // Leave the pedestal at once: the next pop (3 s later) would otherwise be level 2's third star (the top level ends the match).
        tpArena(a.name, spawnA?.first ?: -30.0, spawnA?.second ?: 65.0, spawnA?.third ?: 0.0, 0f, 0f)
        Thread.sleep(1_000)
        E2eReport.check(suite, "a bot standing on a generator picks up the popped stars and the STAR goal levels it up (Lv 1 -> 2)", "SetScore") {
            assertThat(leveled).describedAs("level stayed ${levelOf(a)}; stars=${starCount(a)}; sidebar=${sidebar(a)}").isTrue()
            assertThat(starCount(a)).describedAs("stars are kept by a level-up: ${sidebar(a)}").isGreaterThanOrEqualTo(2)
            assertThat(a.actionBars.any { "★" in it.text && "+" in it.text }).describedAs("no '+N ★' action bar: ${a.actionBars.map { it.text }}").isTrue()
            "A sidebar: ${sidebar(a)}"
        }
        E2eReport.check(suite, "a bot far from every generator stays at 0 stars and Lv 1", "SetScore") {
            assertThat(starCount(c)).describedAs("far bot's sidebar ${sidebar(c)}; pos=${posOf(c)}").isEqualTo(0)
            assertThat(levelOf(c)).isEqualTo(1 to 3)
            "C sidebar: ${sidebar(c)}; pos=${posOf(c)}"
        }

        // A hit (non-lethal damage) never changes stars.
        val carried = starCount(a)
        rcon("damage ${c.name} 1 minecraft:player_attack by ${a.name}")
        Thread.sleep(2_500)
        E2eReport.check(suite, "a hit (and the damage it deals) does not change the star count", "SetScore") {
            assertThat(starCount(a)).describedAs("A before=$carried sidebar=${sidebar(a)}").isEqualTo(carried)
            assertThat(starCount(c)).describedAs("C sidebar=${sidebar(c)}").isEqualTo(0)
            "A=${starCount(a)} C=${starCount(c)}"
        }

        // (b) kill burst + KILL goal: C picks the KILL goal and kills A, who carries stars.
        val replyFrom = c.chat.size
        c.command("astera norma choose kill")
        val choose = c.chatSince(replyFrom)
        // A dies away from its own spawn (centre island, clear of every pedestal) so it cannot respawn onto its own burst.
        tpArena(a.name, 0.5, 65.0, 4.0, 0f, 0f)
        Thread.sleep(1_200)
        val deathSpot = posOf(a)
        // C watches from 4 blocks away (inside entity tracking range) while the burst flies out.
        tpArena(c.name, 4.5, 65.0, 4.0, 0f, 0f)
        Thread.sleep(800)
        val burstEventsFrom = c.entityEvents.size
        val tKill = System.currentTimeMillis()
        val logFromKill = logLineCount()
        rcon("damage ${a.name} 1000 minecraft:magic by ${c.name}")
        a.waitUntil(8_000) { a.deaths.isNotEmpty() }
        val victimCleared = c.waitUntil(6_000) { starCount(a) == 0 }
        E2eReport.check(suite, "a kill bursts the victim's carried stars (victim back to 0)", "SetScore") {
            assertThat(choose.map { clean(it) }.joinToString(" ")).describedAs("no reply to /astera norma choose kill").contains("kill")
            assertThat(victimCleared).describedAs("victim still carries ${starCount(a)} stars: ${sidebar(a)} (carried before: $carried); server log: ${ServerLog.read().lineSequence().drop(logFromKill).filter { it.contains("star", true) || it.contains("ERROR") || it.contains("threw") }.take(12).toList()}").isTrue()
            "victim stars $carried -> ${starCount(a)} ${System.currentTimeMillis() - tKill} ms after the kill"
        }
        val expectedBurst = minOf(carried ?: 0, 6)
        E2eReport.check(suite, "the burst creates N star item entities around the death spot (N = min(stars carried, 6))", "ClientboundAddEntity(ITEM)") {
            val spawned = c.entityEvents.drop(burstEventsFrom).count { it.added && it.type.equals("ITEM", ignoreCase = true) }
            assertThat(expectedBurst).describedAs("victim carried nothing").isGreaterThan(0)
            assertThat(spawned).describedAs("ITEM entities that appeared after the kill").isGreaterThanOrEqualTo(expectedBurst)
            "carried=$carried expected>=$expectedBurst spawned=$spawned"
        }
        E2eReport.check(suite, "the KILL goal levels the killer up (Lv 1 -> 2 after one kill)", "SetScore") {
            assertThat(c.waitUntil(6_000) { levelOf(c)?.first == 2 }).describedAs("killer sidebar ${sidebar(c)}").isTrue()
            "C sidebar: ${sidebar(c)}"
        }
        // Another player walks over the burst: the death spot and a ring around it (the drops lie within ~2.5 blocks).
        val sweep = listOf(0.0 to 0.0, 1.5 to 0.0, -1.5 to 0.0, 0.0 to 1.5, 0.0 to -1.5, 1.1 to 1.1, -1.1 to -1.1, 1.1 to -1.1, -1.1 to 1.1, 2.2 to 0.0, -2.2 to 0.0, 0.0 to 2.2, 0.0 to -2.2)
        var picked = false
        if (deathSpot != null) {
            for ((dx, dz) in sweep) {
                tpArena(c.name, deathSpot.first + dx, deathSpot.second, deathSpot.third + dz, 0f, 0f)
                if (c.waitUntil(1_200) { (starCount(c) ?: 0) > 0 }) { picked = true; break }
            }
        }
        E2eReport.check(suite, "another player picks the burst stars up (killer count rises above 0)", "SetScore") {
            assertThat(deathSpot).describedAs("no death position").isNotNull()
            assertThat(picked).describedAs("killer count stayed ${starCount(c)}; death spot=$deathSpot; sidebar=${sidebar(c)}").isTrue()
            "C sidebar: ${sidebar(c)}; death spot=$deathSpot"
        }

        // (c) the top Norma level wins: C (Lv 2, goal STAR again) takes pedestal stars until 3 are carried.
        tpArena(c.name, 12.5, 65.0, 0.5, 0f, 0f)
        val ended = awaitNoMatch(60_000, keepAlive = both)
        E2eReport.check(suite, "reaching the top Norma level ends the match by itself", "RCON astera match list") {
            assertThat(ended).describedAs("match still listed; C=${sidebar(c)} A=${sidebar(a)}; list=${matchLines()}").isTrue()
            "match gone"
        }
        both.forEach { b -> b.waitUntil(40_000) { b.hotbar()[8] != -1 && b.sidebarTitle() == lobbyTitle } }
        Thread.sleep(2_000)
        E2eReport.check(suite, "the result names the Norma-level win", "SystemChat") {
            val chat = c.chat.drop(chatFrom.getValue(c)).map { clean(it.text) }
            assertThat(chat.any { it.contains("Norma level", true) && it.contains("reached", true) })
                .describedAs("no 'reached Norma level' reason:\n${quote(chat, 40)}").isTrue()
            quote(chat.filter { it.contains("Norma level", true) || it.contains("Match Summary") }, 6)
        }
        for (b in both) {
            E2eReport.check(suite, "${b.name}: the lobby sidebar has no star line", "SetScore") {
                assertThat(b.sidebarTitle()).describedAs("still on the match sidebar: ${sidebar(b)}").isEqualTo(lobbyTitle)
                assertThat(sidebar(b).filter { STAR_WORD.containsMatchIn(it) || it.contains("Lv ") }).describedAs("lobby sidebar ${sidebar(b)}").isEmpty()
                quote(sidebar(b))
            }
            val from = b.chat.size
            b.command("astera economy balance")
            val reply = b.chatSince(from, minNew = 1).map { clean(it) }
            E2eReport.check(suite, "${b.name}: the wallet lists no STAR currency", "SystemChat astera economy balance") {
                assertThat(reply).describedAs("no balance reply").isNotEmpty()
                assertThat(reply.filter { STAR_WORD.containsMatchIn(it) }).describedAs("wallet reply mentions stars: $reply").isEmpty()
                quote(reply)
            }
            val ending = b.chat.drop(chatFrom.getValue(b)).map { clean(it.text) }
            E2eReport.check(suite, "${b.name}: no chat line during or after the match grants or mentions stars", "SystemChat") {
                assertThat(ending.filter { STAR_WORD.containsMatchIn(it) }).describedAs("star amounts in chat: ${quote(ending, 40)}").isEmpty()
                "ok"
            }
        }
    }
}
