package com.astarworks.astera.e2e

import com.astarworks.astera.bots.BotBrain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * ADR-0247: two AI bots (real protocol clients driven by [BotBrain], the same code the `bot-runner` service uses) play
 * a whole Norma 1v1 against each other. Nothing is forced over RCON: the AI has to find the enemy, walk to it, swing /
 * shoot, kill it, survive the death cam and respawn until the match ends on its own (content timers are shortened by
 * docker/e2e-tune-content.sh: 3 kills or 150 s). Then: a winner is declared, summary and rewards arrive, both bots are
 * back in the lobby.
 *
 * The only RCON use is read-only (`astera match list`, `data get entity`) plus handing out the starter weapons when a
 * bot arrived unarmed (a real account gets them with its first-join grant).
 */
class BotVsBotE2eTest : PlaythroughBase() {

    private val suite = "Bot vs bot"
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
        bots.forEach { brains += BotBrain(it).start() }

        // --- queue -> handoff -> ACTIVE -------------------------------------------------------------------------
        val tQueue = System.nanoTime()
        bots.forEach { it.command("astera queue join norma") }
        val acks = bots.map { b -> b.chatSince(chatFrom.getValue(b)) }
        val active = awaitMatch("ACTIVE", 70_000, keepAlive = bots)
        E2eReport.check(suite, "two bots queue norma and the match goes ACTIVE", "RCON astera match list") {
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
        val tPlay = System.currentTimeMillis()
        val deadline = tPlay + 300_000
        var firstKillMs = -1L
        while (System.currentTimeMillis() < deadline && matchLines().isNotEmpty()) {
            if (firstKillMs < 0 && bots.sumOf { it.deaths.size } > deathsBefore) firstKillMs = System.currentTimeMillis() - tPlay
            Thread.sleep(1_000)
        }
        val ended = matchLines().isEmpty()
        brains.forEach { it.mode = BotBrain.Mode.IDLE }
        val kills = bots.sumOf { it.deaths.size } - deathsBefore
        val brainSummary = brains.mapIndexed { i, b -> "${bots[i].name}: ${b.summary()}" }
        E2eReport.notes += "Bot vs bot: first kill after ${firstKillMs}ms, total deaths $kills, ${brainSummary.joinToString(" | ")}"
        E2eReport.check(suite, "the AI produces at least one kill (no RCON damage)", "PlayerCombatKill / death cam") {
            assertThat(kills).describedAs("no bot ever died in ${(System.currentTimeMillis() - tPlay) / 1000}s. $brainSummary; positions=${bots.map { posOf(it) }}; sidebar=${sidebar(bots[0])}").isGreaterThanOrEqualTo(1)
            "deaths=$kills first at ${firstKillMs}ms; ${brainSummary.joinToString(" | ")}"
        }
        E2eReport.check(suite, "both bots actually attacked (swings or shots sent)", "bot counters") {
            brains.forEachIndexed { i, b -> assertThat(b.swings.get() + b.shots.get()).describedAs("${bots[i].name} never attacked: ${b.summary()}").isGreaterThan(0) }
            brainSummary.joinToString(" | ")
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
        E2eReport.check(suite, "a winner is declared: the summary names a team and the scores differ", "SystemChat") {
            val summary = chats.getValue(bots[0])
            val reason = summary.firstOrNull { it.contains("reached the norma") || it.contains("was ahead") }
            val score = summary.firstOrNull { it.startsWith("Score") }.orEmpty()
            val numbers = Regex("(Red|Blue)\\D*(\\d+)").findAll(score).associate { it.groupValues[1] to it.groupValues[2].toInt() }
            assertThat(reason).describedAs("no winner reason (draw?):\n${quote(summary, 40)}").isNotNull()
            assertThat(numbers.values.toSet().size).describedAs("scores tied: '$score'").isGreaterThan(1)
            val winner = numbers.maxByOrNull { it.value }?.key
            assertThat(reason).describedAs("reason does not name the leading team $winner").contains(winner!!)
            "reason='$reason'; score='$score'; winner=$winner"
        }
        checkEnding(suite, bots, chatFrom, lobbyTitle)
        E2eReport.check(suite, "server log stayed free of errors during the match", "server log") {
            val errs = serverErrorsSince(logFrom)
            assertThat(errs).describedAs("server errors:\n${quote(errs, 20)}").isEmpty()
            "clean"
        }
    }
}
