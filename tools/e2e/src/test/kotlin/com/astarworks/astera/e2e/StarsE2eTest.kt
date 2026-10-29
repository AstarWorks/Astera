package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType
import org.junit.jupiter.api.Test

/**
 * In-match stars and Norma levels (docs/game-design/stars-and-norma.md, ADR-0271): a star is an item of the match,
 * never carried out of it.
 *
 * Two bots play a Norma match with the e2e-tuned content (docker/e2e-tune-content.sh): generators pop every 3 s,
 * max Norma level 3, star goals 2 then 3, kill goals 1 then 2. Asserted: the match sidebar shows 0 stars, Lv 1/3 and
 * the goal at the start; a bot standing on a generator picks the popped stars up and levels up through the STAR goal;
 * a bot far from every generator stays at 0; a hit changes nothing; a kill bursts the victim's stars (victim 0, the
 * other bot walks over the drops) and the KILL goal levels the killer up; reaching the top level ends the match; and
 * the level-up and the goal pick are presented (chat broadcast, action bar, sounds); stars are collected only by
 * the real item pickup (the item is removed, never put in an inventory); afterwards the lobby sidebar has no star line, the wallet lists no STAR and no chat line mentions stars.
 */
class StarsE2eTest : PlaythroughBase() {

    private val suite = "Stars"

    /**
     * The carried-stars number: the `* N` after the level on the `Norma Lv 1/3 | * N` sidebar row. It stays on that row under
     * either goal (STAR or KILL) and at the top level.
     */
    private fun starCount(b: Bot): Int? =
        sidebar(b).firstNotNullOfOrNull { Regex("Lv\\s*\\d+/\\d+\\s*\\|\\s*\\*\\s*(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** `Lv 2/3` on the sidebar as (level, max), or null. */
    private fun levelOf(b: Bot): Pair<Int, Int>? =
        sidebar(b).firstNotNullOfOrNull { Regex("Lv\\s*(\\d+)/(\\d+)").find(it)?.let { m -> m.groupValues[1].toInt() to m.groupValues[2].toInt() } }

    /** The `Goal: ...` sidebar line, or null. */
    private fun goalLine(b: Bot): String? = sidebar(b).firstOrNull { it.contains("Goal:") }

    /** Dropped-item entities (a star drop is a glowing NETHER_STAR item) this bot currently sees within [r] blocks of the absolute (x, z). */
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
        both.forEach { it.command("astera queue join carry-tnt") }

        val active = awaitMatch("ACTIVE", 60_000, keepAlive = both)
        E2eReport.check(suite, "a two-player Norma match goes ACTIVE", "RCON astera match list") {
            assertThat(active).describedAs("match never ACTIVE; chat=${a.chatTexts().takeLast(6)}").isNotNull()
            "line='${active?.raw}'"
        }
        if (active == null) return
        // Nobody stands on a pedestal. Keep the pre-pedestal steps short: every 3 s each generator holds one more star.
        both.forEach { b -> b.waitUntil(5_000) { goalLine(b) != null } }

        E2eReport.check(suite, "the match sidebar shows Lv 1/3 and the goal Stars 0/2 (0 carried) for everyone at the start", "SetScore") {
            for (b in both) {
                assertThat(starCount(b)).describedAs("${b.name} sidebar ${sidebar(b)}").isEqualTo(0)
                assertThat(levelOf(b)).describedAs("${b.name} sidebar ${sidebar(b)}").isEqualTo(1 to 3)
                assertThat(sidebar(b).any { Regex("Goal:\\s*Stars\\s*0/2").containsMatchIn(it) }).describedAs("no star goal line: ${sidebar(b)}").isTrue()
                assertThat(rawKeys(sidebar(b))).isEmpty()
            }
            quote(sidebar(a))
        }

        // The goal is chosen with /astera norma choose (nothing opens over the screen at match start; the picker window has no caller).
        E2eReport.check(suite, "/astera norma choose kill switches the goal to Kills 0/1 and the star count stays on the Norma row; choosing star keeps Stars 0/2", "SystemChat -> SetScore") {
            val before = c.chat.size
            c.command("astera norma choose kill")
            assertThat(c.waitUntil(6_000) { sidebar(c).any { Regex("Goal:\\s*Kills\\s*0/1").containsMatchIn(it) } })
                .describedAs("C goal did not become Kills 0/1: ${sidebar(c)}; chat=${c.chatSince(before).map { clean(it) }}").isTrue()
            assertThat(starCount(c)).describedAs("the carried-star count must stay on the Norma row under the Kills goal: ${sidebar(c)}").isEqualTo(0)
            assertThat(both.all { it.screens.none { s -> s.title.contains("Norma goal") } }).describedAs("a picker window opened by itself: ${both.map { it.screens.map { s -> s.title } }}").isTrue()
            a.command("astera norma choose star")
            Thread.sleep(800)
            assertThat(sidebar(a).any { Regex("Goal:\\s*Stars\\s*0/2").containsMatchIn(it) }).describedAs("A sidebar ${sidebar(a)}").isTrue()
            "C: ${sidebar(c)}; A: ${sidebar(a)}"
        }
        // C goes back to STAR for the pedestal run.
        val cmdFrom = c.chat.size
        c.command("astera norma choose star")
        E2eReport.check(suite, "/astera norma choose star still works", "SystemChat") {
            val reply = c.chatSince(cmdFrom).map { clean(it) }.joinToString(" ")
            assertThat(reply).describedAs("no reply").containsIgnoringCase("star")
            assertThat(c.waitUntil(4_000) { sidebar(c).any { Regex("Goal:\\s*Stars").containsMatchIn(it) } }).describedAs("C sidebar ${sidebar(c)}").isTrue()
            reply
        }

        // (generator star is a visible entity) A stands 5 blocks off the east generator and must see the dropped star there.
        val spawnA = localPosOf(a)
        tpArena(a.name, 17.5, 65.0, 0.5, 0f, 0f)
        val seen = a.waitUntil(10_000) { a.nudge(); c.nudge(); itemsNear(a, 12.5 + originX, 0.5 + originZ, 2.5) >= 1 }
        E2eReport.check(suite, "a generator star appears in the world as an item entity at the generator", "ClientboundAddEntity(ITEM)") {
            assertThat(seen).describedAs("no ITEM entity near local (12.5, 0.5), slot ($originX, $originZ); entities=${a.entities.values.map { it.type }.groupingBy { it }.eachCount()}").isTrue()
            "items near generator: ${itemsNear(a, 12.5 + originX, 0.5 + originZ, 2.5)}"
        }

        // (a) + STAR-goal level-up: A stands on the east generator (12.5, 65, 0.5) and takes what pops; 2 stars clear level 1.
        val spawnC = localPosOf(c)
        tpArena(a.name, 12.5, 65.0, 0.5, 0f, 0f)
        // Meeting the goal only makes A ready ("Norma goal met" action bar, no level yet); the level rises at the Norma check in A's own base.
        val ready = a.waitUntil(40_000) { a.nudge(); c.nudge(); a.actionBars.any { "Norma goal met" in it.text } }
        // Leave the pedestal at once (the next pop, 3 s later, would be level 2's third star; the top level ends the match) for the
        // centre island, clear of every pedestal and provably outside the own base zone (norma_check_radius 16 around the team spawn):
        // inside the zone the Norma check fires on its own, so "ready, still Lv 1" can only be observed outside it.
        val awayX = 0.5
        val awayZ = 4.0
        val homeDist = spawnA?.let { Math.hypot(it.first - awayX, it.third - awayZ) } ?: 0.0
        tpArena(a.name, awayX, 65.0, awayZ, 0f, 0f)
        Thread.sleep(1_500)
        val stillLv1 = levelOf(a)?.first == 1
        val lv1Sidebar = sidebar(a)
        // Back at the own base the check fires by itself; the explicit command is the same check, so the outcome does not depend on timing.
        tpArena(a.name, spawnA?.first ?: -30.0, spawnA?.second ?: 65.0, spawnA?.third ?: 0.0, 0f, 0f)
        Thread.sleep(500)
        a.command("astera norma check")
        val leveled = a.waitUntil(10_000) { a.nudge(); c.nudge(); levelOf(a)?.first == 2 }
        E2eReport.check(suite, "meeting the STAR goal only readies the player; the level stays 1 until the check at the own base", "SetScore") {
            assertThat(ready).describedAs("no 'Norma goal met' action bar: ${a.actionBars.map { it.text }}").isTrue()
            assertThat(homeDist).describedAs("the away spot must be outside the 16-block base zone").isGreaterThan(16.0)
            assertThat(stillLv1).describedAs("level rose outside the own base: $lv1Sidebar").isTrue()
            "ok"
        }
        E2eReport.check(suite, "a bot standing on a generator picks up the popped stars and the STAR goal levels it up (Lv 1 -> 2)", "SetScore") {
            assertThat(leveled).describedAs("level stayed ${levelOf(a)}; stars=${starCount(a)}; sidebar=${sidebar(a)}").isTrue()
            assertThat(starCount(a)).describedAs("stars are kept by a level-up: ${sidebar(a)}").isGreaterThanOrEqualTo(2)
            assertThat(a.actionBars.any { "★" in it.text && "+" in it.text }).describedAs("no '+N ★' action bar: ${a.actionBars.map { it.text }}").isTrue()
            "A sidebar: ${sidebar(a)}"
        }
        E2eReport.check(suite, "the level-up is announced to the whole match in chat (and with a sound for the player)", "SystemChat") {
            for (b in both) {
                assertThat(b.waitUntil(4_000) { b.chat.drop(chatFrom.getValue(b)).any { clean(it.text).contains("cleared the Norma", true) && clean(it.text).contains("Lv 2") } })
                    .describedAs("${b.name}: no level-up broadcast; chat=${b.chatTexts().takeLast(6)}").isTrue()
            }
            "ok"
        }
        E2eReport.check(suite, "a bot far from every generator stays at 0 stars and Lv 1", "SetScore") {
            assertThat(starCount(c)).describedAs("far bot's sidebar ${sidebar(c)}; pos=${localPosOf(c)}").isEqualTo(0)
            assertThat(levelOf(c)).isEqualTo(1 to 3)
            "C sidebar: ${sidebar(c)}; pos=${localPosOf(c)}"
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
        val deathSpot = localPosOf(a)
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
        val expectedBurst = minOf(carried ?: 0, 16)
        E2eReport.check(suite, "the burst creates N star item entities around the death spot (N = min(stars carried, 16))", "ClientboundAddEntity(ITEM)") {
            val spawned = c.entityEvents.drop(burstEventsFrom).count { it.added && it.type.equals("ITEM", ignoreCase = true) }
            assertThat(expectedBurst).describedAs("victim carried nothing").isGreaterThan(0)
            assertThat(spawned).describedAs("ITEM entities that appeared after the kill").isGreaterThanOrEqualTo(expectedBurst)
            "carried=$carried expected>=$expectedBurst spawned=$spawned"
        }
        // The kill only readies the killer; the level rises at the Norma check in the killer's own base.
        tpArena(c.name, spawnC?.first ?: 30.0, spawnC?.second ?: 65.0, spawnC?.third ?: 0.0, 0f, 0f)
        Thread.sleep(500)
        c.command("astera norma check")
        E2eReport.check(suite, "the KILL goal levels the killer up (Lv 1 -> 2 after one kill and the check at base)", "SetScore") {
            assertThat(c.waitUntil(6_000) { levelOf(c)?.first == 2 }).describedAs("killer sidebar ${sidebar(c)}").isTrue()
            "C sidebar: ${sidebar(c)}"
        }
        // Another player walks over the burst: the death spot and a ring around it (the fountain lands drops within ~4.5 blocks).
        val sweep = listOf(0.0 to 0.0, 1.5 to 0.0, -1.5 to 0.0, 0.0 to 1.5, 0.0 to -1.5, 1.1 to 1.1, -1.1 to -1.1, 1.1 to -1.1, -1.1 to 1.1, 2.2 to 0.0, -2.2 to 0.0, 0.0 to 2.2, 0.0 to -2.2, 3.2 to 0.0, -3.2 to 0.0, 0.0 to 3.2, 0.0 to -3.2, 2.4 to 2.4, -2.4 to -2.4, 2.4 to -2.4, -2.4 to 2.4)
        var picked = false
        if (deathSpot != null) {
            for ((dx, dz) in sweep) {
                tpArena(c.name, deathSpot.first + dx, deathSpot.second, deathSpot.third + dz, 0f, 0f)
                if (c.waitUntil(1_200) { (starCount(c) ?: 0) > 0 || levelOf(c)?.first == 3 }) { picked = true; break }
            }
        }
        E2eReport.check(suite, "another player picks the burst stars up (killer count rises above 0)", "SetScore") {
            assertThat(deathSpot).describedAs("no death position").isNotNull()
            assertThat(picked).describedAs("killer count stayed ${starCount(c)}; death spot=$deathSpot; sidebar=${sidebar(c)}").isTrue()
            "C sidebar: ${sidebar(c)}; death spot=$deathSpot"
        }

        // (c) the top Norma level no longer wins a match (it unlocks ultimates): end it from the console and check the lobby afterwards.
        matchLines().forEach { rcon("astera match end ${it.id}") }
        val ended = awaitNoMatch(60_000, keepAlive = both)
        E2eReport.check(suite, "an admin end returns the match to the lobby", "RCON astera match list") {
            assertThat(ended).describedAs("match still listed; C=${sidebar(c)} A=${sidebar(a)}; list=${matchLines()}").isTrue()
            "match gone"
        }
        both.forEach { b -> b.waitUntil(40_000) { b.hotbar()[8] != -1 && b.sidebarTitle() == lobbyTitle } }
        Thread.sleep(2_000)
        for (b in both) {
            E2eReport.check(suite, "${b.name}: the lobby sidebar has no star line", "SetScore") {
                assertThat(b.sidebarTitle()).describedAs("still on the match sidebar: ${sidebar(b)}").isEqualTo(lobbyTitle)
                assertThat(sidebar(b).filter { STAR_WORD.containsMatchIn(it) || Regex("Lv\\s*\\d+/\\d+").containsMatchIn(it) }).describedAs("lobby sidebar ${sidebar(b)}").isEmpty()
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
                // The Norma explainers and the reply to choosing the STAR goal ("Norma goal: star.") name stars, they grant nothing.
                assertThat(ending.filter { grantsStars(it) }).describedAs("star amounts in chat: ${quote(ending, 40)}").isEmpty()
                "ok"
            }
        }
    }
}
