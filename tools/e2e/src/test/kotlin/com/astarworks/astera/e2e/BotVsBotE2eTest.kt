package com.astarworks.astera.e2e

import com.astarworks.astera.bots.BotBrain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * ADR-0247: two AI bots (real protocol clients driven by [BotBrain], the same code the `bot-runner` service uses) play
 * a whole Norma 1v1 against each other. Nothing is forced over RCON: the AI has to find the enemy, walk to it, swing /
 * shoot, kill it, survive the death cam and respawn until the clock ends the match on its own (content timers are shortened by
 * docker/e2e-tune-content.sh: the carry-tnt clock, 240 s). Then: a winner is declared, summary and rewards arrive, both bots are
 * back in the lobby.
 *
 * The only RCON use is read-only (`astera match list`, `data get entity`) plus handing out the starter weapons when a
 * bot arrived unarmed (a real account gets them with its first-join grant).
 */
class BotVsBotE2eTest : PlaythroughBase() {

    private val suite = "Bot vs bot"
    private companion object {
        const val NAV_MAX_SEARCH_US = 100_000L
        const val NAV_MAX_AVG_NODES = 600
    }
    private val brains = mutableListOf<BotBrain>()

    @AfterEach
    fun stopBrains() {
        brains.forEach { it.close() }
        brains.clear()
    }

    @Test
    fun `two AI bots play a full norma match to a winner`() {
        val logFrom = logLineCount()
        val bots = (1..2).map { bot("Ai$it", PackPolicy.DECLINE) }
        bots.forEach { b -> b.waitUntil(15_000) { b.sidebarLines().isNotEmpty() && b.hotbar()[8] != -1 } }
        Thread.sleep(1_500)
        val lobbyTitle = bots[0].sidebarTitle()
        val chatFrom = bots.associateWith { it.chat.size }
        // Like the production bot-runner, the brains know the lane's objective (here: the enemy spawn) from the same content
        // the server runs; without it they roam at random, which on the 140-block sky-island arena rarely ends in a meeting.
        val objectives = com.astarworks.astera.bots.StageObjectives.load(java.io.File(System.getProperty("astera.live.contentDir", "/workspace/content")), "carry-tnt")
        // chooseNormaGoal = false: this loop sends `norma choose kill` itself so stars cannot end the match before a fight.
        bots.forEach { brains += BotBrain(it, objectives = objectives, chooseNormaGoal = false).start() }

        // --- queue -> handoff -> ACTIVE -------------------------------------------------------------------------
        val tQueue = System.nanoTime()
        bots.forEach { it.command("astera queue join carry-tnt") }
        val acks = bots.map { b -> b.chatSince(chatFrom.getValue(b)) }
        val active = awaitMatch("ACTIVE", 70_000, keepAlive = bots)
        E2eReport.check(suite, "two bots queue carry-tnt and the match goes ACTIVE", "RCON astera match list") {
            acks.forEachIndexed { i, a -> assertThat(a).describedAs("no queue reply for bot ${i + 1}").isNotEmpty() }
            assertThat(active).describedAs("never ACTIVE; chat=${bots[0].chatTexts().takeLast(6)}").isNotNull()
            "queue -> ACTIVE in ${(System.nanoTime() - tQueue) / 1_000_000_000}s; line='${active?.raw}'"
        }
        if (active == null) return
        Thread.sleep(1_500)

        // The loaner kit / starter grant normally arms them; hand out the starter pair only if a hotbar is empty.
        val armedBy = bots.associate { b ->
            val hasWeapon = b.waitUntil(3_000) { b.hotbar().take(8).any { it != -1 } }
            if (!hasWeapon) {
                rcon("astera items give ${b.name} knight-sword")
                rcon("astera items give ${b.name} pistol")
                b.waitUntil(4_000) { b.hotbar().take(8).any { it != -1 } }
            }
            b.name to if (hasWeapon) "game kit" else "rcon starter pair"
        }
        // Consumables: a player buys a heal-potion in the shop and carries it in; here it is handed over by an item grant
        // so the retreat logic has something to drink. The brain must not mistake it for a weapon.
        bots.forEach { rcon("astera items give ${it.name} heal-potion") }
        Thread.sleep(800)
        E2eReport.check(suite, "both bots enter the match armed", "SetContainerContent") {
            bots.forEach { assertThat(it.hotbar().take(8).any { id -> id != -1 }).describedAs("${it.name} has no weapon: ${it.hotbar()}").isTrue() }
            "armed by: $armedBy; hotbars=${bots.map { it.hotbar() }}"
        }
        E2eReport.check(suite, "bots start on opposite spawns with the match sidebar", "RCON data get Pos + SetObjective") {
            val positions = bots.map { posOf(it) }
            assertThat(positions.all { it != null }).describedAs("positions $positions").isTrue()
            assertThat(bots[0].sidebarTitle()).describedAs("still the lobby sidebar").isNotEqualTo(lobbyTitle)
            "positions=$positions sidebar=${sidebar(bots[0])}"
        }

        // --- the AI plays ---------------------------------------------------------------------------------------
        val deathsBefore = bots.sumOf { it.deaths.size }
        brains.forEach { it.mode = BotBrain.Mode.FIGHT }
        // The default Norma goal is STAR and the e2e star generators pay every 3 s, so an idle pair reaches level 3 in ~13 s
        // and the match ends before the bots have crossed the arena. Pick the KILL goal (as a player would from the picker),
        // and again after every level-up, so the match can only end through fighting.
        var lastChooseAt = 0L
        val tPlay = System.currentTimeMillis()
        // carry-tnt tune: 420 s limit + up to 60 s overtime (count model), so wait for the clock past that.
        val deadline = tPlay + 520_000
        var firstKillMs = -1L
        val track = mutableListOf<String>()
        var stallAt: Triple<Double, Double, Double>? = null
        while (System.currentTimeMillis() < deadline && matchLines().isNotEmpty()) {
            if (System.currentTimeMillis() - lastChooseAt > 2_000) {
                lastChooseAt = System.currentTimeMillis()
                bots.forEach { it.command("astera norma choose kill") }
            }
            if (firstKillMs < 0 && bots.sumOf { it.deaths.size } > deathsBefore) firstKillMs = System.currentTimeMillis() - tPlay
            if ((System.currentTimeMillis() - tPlay) / 1_000 % 10 == 0L) {
                track += "${(System.currentTimeMillis() - tPlay) / 1_000}s:" + bots.joinToString("/") { b -> posOf(b)?.let { "(%.0f,%.0f)".format(it.first, it.third) } ?: "?" }
            }
            if (stallAt == null && System.currentTimeMillis() - tPlay > 60_000) stallAt = posOf(bots[0])
            Thread.sleep(1_000)
        }
        if (bots.sumOf { it.deaths.size } == deathsBefore) stallAt?.let { (px, _, pz) ->
            // Nobody fought: dump the blocks around where bot 1 was standing at +60 s (rows = z, columns = x, one grid per y).
            val bx = kotlin.math.floor(px).toInt()
            val bz = kotlin.math.floor(pz).toInt()
            val grids = (64..67).joinToString(" | ") { y ->
                "y=$y: " + (bz - 3..bz + 3).joinToString("/") { z -> (bx - 4..bx + 4).joinToString("") { x -> if (arenaSolid(x, y, z)) "#" else "." } }
            }
            E2eReport.notes += "Bot vs bot stall around ($bx, $bz), x from ${bx - 4}, z from ${bz - 3}: $grids"
        }
        val ended = matchLines().isEmpty()
        brains.forEach { it.mode = BotBrain.Mode.IDLE }
        val kills = bots.sumOf { it.deaths.size } - deathsBefore
        val brainSummary = brains.mapIndexed { i, b -> "${bots[i].name}: ${b.summary()}" }
        E2eReport.notes += "Bot vs bot: first kill after ${firstKillMs}ms, total deaths $kills, ${brainSummary.joinToString(" | ")}; track=${track.distinct().joinToString(" ")}; trace0=${brains.firstOrNull()?.traceText()}"
        E2eReport.check(suite, "the AI produces at least one kill (no RCON damage)", "PlayerCombatKill / death cam") {
            assertThat(kills).describedAs("no bot ever died in ${(System.currentTimeMillis() - tPlay) / 1000}s. $brainSummary; positions=${bots.map { posOf(it) }}; sidebar=${sidebar(bots[0])}").isGreaterThanOrEqualTo(1)
            "deaths=$kills first at ${firstKillMs}ms; ${brainSummary.joinToString(" | ")}"
        }
        E2eReport.check(suite, "a retreating bot drinks its heal-potion and its HP rises", "bot counters + SetHealth") {
            val drank = brains.withIndex().filter { it.value.drinks.get() > 0 }
            assertThat(drank).describedAs("no bot ever retreated and drank (hp history ${bots.map { it.healthHistory.takeLast(6) }}); $brainSummary").isNotEmpty()
            drank.forEach { (i, b) -> assertThat(b.peakHpAfterDrink).describedAs("${bots[i].name} drank at ${b.lastDrinkHp} HP but never healed").isGreaterThan(b.lastDrinkHp) }
            drank.joinToString { (i, b) -> "${bots[i].name}: drinks=${b.drinks.get()} hp ${b.lastDrinkHp} -> peak ${b.peakHpAfterDrink}" }
        }
        // Pathfinding budget. Observed 2026-10: maxSearchUs 721..8056, avgNodes 31..36, 35..59 searches. About 10x headroom, so only a real regression trips it.
        E2eReport.check(suite, "A* stays within its budget (slowest search, average expanded nodes)", "Navigator counters") {
            brains.forEachIndexed { i, b ->
                val avg = b.nav.expandedTotal.get() / b.nav.searches.get().coerceAtLeast(1)
                assertThat(b.nav.maxSearchMicros).describedAs("${bots[i].name} slowest search").isLessThan(NAV_MAX_SEARCH_US)
                assertThat(avg).describedAs("${bots[i].name} average expanded nodes").isLessThan(NAV_MAX_AVG_NODES)
            }
            brains.mapIndexed { i, b -> "${bots[i].name}: searches=${b.nav.searches.get()} maxSearchUs=${b.nav.maxSearchMicros} avgNodes=${b.nav.expandedTotal.get() / b.nav.searches.get().coerceAtLeast(1)}" }.joinToString(" | ")
        }
        E2eReport.check(suite, "both bots actually attacked (swings or shots sent)", "bot counters") {
            brains.forEachIndexed { i, b -> assertThat(b.swings.get() + b.shots.get()).describedAs("${bots[i].name} never attacked: ${b.summary()}").isGreaterThan(0) }
            brainSummary.joinToString(" | ")
        }
        E2eReport.check(suite, "the sword is actually swung (the starter kit carries a knight-sword, melee closes in instead of kiting)", "bot counters") {
            assertThat(brains.sumOf { it.swings.get() }).describedAs("no bot ever swung its sword: $brainSummary").isGreaterThan(0)
            brains.joinToString(" | ") { "swings=${it.swings.get()}" }
        }
        E2eReport.check(suite, "the match ends by itself", "RCON astera match list") {
            assertThat(ended).describedAs("match still running after ${(System.currentTimeMillis() - tPlay) / 1000}s: ${matchLines()}; sidebar=${sidebar(bots[0])}").isTrue()
            "match gone after ${(System.currentTimeMillis() - tPlay) / 1000}s"
        }
        if (!ended) {
            // Cleanup only (not a kill): do not leave a running match behind for the next suite.
            matchLines().forEach { rcon("astera match end ${it.id}") }
            awaitNoMatch(20_000)
        }

        // --- a winner, rewards, back in the lobby ----------------------------------------------------------------
        bots.forEach { b -> b.waitUntil(40_000) { b.hotbar()[8] != -1 && b.sidebarTitle() == lobbyTitle } }
        val chats = bots.associateWith { b -> b.chat.drop(chatFrom.getValue(b)).map { clean(it.text) } }
        E2eReport.check(suite, "a winner is declared: the time-limit summary names the team that was ahead", "SystemChat") {
            val summary = chats.getValue(bots[0])
            val reason = summary.firstOrNull { it.contains("was ahead") }
            val score = summary.firstOrNull { it.startsWith("Score") }.orEmpty()
            assertThat(reason).describedAs("no winner reason (draw?):\n${quote(summary, 40)}").isNotNull()
            // The clock ranks the teams by the objective, then stars carried, then kills: the score line may read 0-0 on deliveries.
            assertThat(reason).describedAs("winner reason names no team").containsPattern("Red|Blue")
            "reason='$reason'; score='$score'"
        }
        checkEnding(suite, bots, chatFrom, lobbyTitle)
        E2eReport.check(suite, "server log stayed free of errors during the match", "server log") {
            val errs = serverErrorsSince(logFrom)
            assertThat(errs).describedAs("server errors:\n${quote(errs, 20)}").isEmpty()
            "clean"
        }
    }
}
