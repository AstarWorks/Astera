package com.astarworks.astera.e2e

import com.astarworks.astera.bots.ObjectiveWalker
import com.astarworks.astera.bots.StageObjectives
import org.assertj.core.api.Assertions.assertThat
import java.io.File

/**
 * Shared frame of the objective-mode playthroughs (carry-tnt, capture-wool, steal-jewelry, battle-loyal): two protocol bots queue
 * into the lane, the match goes ACTIVE, one bot (the red side) drives the mode's objective by walking the stage's regions with the
 * A* [ObjectiveWalker] while the other stands still, and the match must end by itself with the usual summary / rewards / way back.
 *
 * Region coordinates come from the same tuned `content/` the server runs (StageObjectives loads the lane's stage), so the tests
 * follow whichever compact stage copy the e2e image bakes for the lane (docker/e2e-tune-content.sh).
 */
abstract class ObjectivePlaythroughBase : PlaythroughBase() {

    protected class Ctx(
        val suite: String,
        val lane: String,
        val bots: List<Bot>,
        val lobbyTitle: String?,
        val chatFrom: Map<Bot, Int>,
        val logFrom: Int,
        val objectives: StageObjectives,
        val actor: Bot,
        val idle: Bot,
        val walker: ObjectiveWalker,
        val originX: Double,
        val originZ: Double,
    ) {
        /** World (x, z) of the stage region [name]: the stage is stage-local, the match lives at the slot origin. */
        fun zone(name: String): Pair<Double, Double> =
            (objectives.zoneCentre(name) ?: error("stage has no region '$name'")).let { (zx, zz) -> zx + originX to zz + originZ }
        fun chat(b: Bot = actor): List<String> = b.chat.drop(chatFrom.getValue(b)).map { it.text.replace(Regex("§.|\u001B\\[[0-9;]*m"), "") }
        fun chatCount(needle: String, b: Bot = actor): Int = chat(b).count { it.contains(needle, ignoreCase = true) }
    }

    protected fun contentDir(): File = File(System.getProperty("astera.live.contentDir", "/workspace/content"))

    /** Queues two bots into [lane], waits for ACTIVE and sorts them into the red actor and the blue idler. Null when it never started. */
    protected fun startMatch(suite: String, lane: String, namePrefix: String): Ctx? {
        val logFrom = logLineCount()
        val bots = (1..2).map { bot("$namePrefix$it", PackPolicy.DECLINE) }
        bots.forEach { b -> b.waitUntil(15_000) { b.sidebarLines().isNotEmpty() && b.hotbar()[8] != -1 } }
        Thread.sleep(1_500)
        val lobbyTitle = bots[0].sidebarTitle()
        val chatFrom = bots.associateWith { it.chat.size }

        val tQueue = System.nanoTime()
        bots.forEach { it.command("astera queue join $lane") }
        val active = awaitMatch("ACTIVE", 70_000, keepAlive = bots)
        E2eReport.check(suite, "queue forms a $lane match and it goes ACTIVE (pacing)", "RCON astera match list") {
            assertThat(active).describedAs("never ACTIVE; list=${matchLines()}; chat=${bots[0].chatTexts().takeLast(6)}").isNotNull()
            "queue -> ACTIVE in ${(System.nanoTime() - tQueue) / 1_000_000_000}s; line='${active?.raw}'"
        }
        if (active == null) return null
        Thread.sleep(2_500)

        val sideOf = bots.associateWith { b -> localPosOf(b)?.let { if (it.first < 0) "red" else "blue" } }
        val actor = bots.firstOrNull { sideOf[it] == "red" }
        val idle = bots.firstOrNull { sideOf[it] == "blue" }
        E2eReport.check(suite, "the two bots start on opposite sides", "RCON data get Pos") {
            assertThat(listOfNotNull(actor, idle)).describedAs("sides: ${sideOf.mapKeys { it.key.name }}").hasSize(2)
            "red=${actor?.name} blue=${idle?.name}"
        }
        val objectives = StageObjectives.load(contentDir(), lane)
        E2eReport.check(suite, "the lane's stage and regions load from the shipped content (bot navigation data)", "content/stages") {
            assertThat(objectives).describedAs("StageObjectives.load(${contentDir()}, $lane) failed").isNotNull()
            "stage regions loaded for $lane"
        }
        if (actor == null || idle == null || objectives == null) return null
        E2eReport.check(suite, "both bots spawn standing on a solid island block in the arenas world", "RCON data get Dimension/Pos + execute unless block") {
            val problems = bots.mapNotNull { islandStanceProblem(it) }
            assertThat(problems).describedAs("bots not on an arena island: $problems").isEmpty()
            "on island: ${bots.map { it.name to posOf(it) }}"
        }
        // Stars are picked up on the way; the Norma goal KILL keeps them from levelling the actor up to a Norma win before the objective.
        val walker = ObjectiveWalker(actor, objectives, originX = originX.toDouble(), originZ = originZ.toDouble(), periodic = { bots.forEach { it.command("astera norma choose kill") } })
        return Ctx(suite, lane, bots, lobbyTitle, chatFrom, logFrom, objectives, actor, idle, walker, originX.toDouble(), originZ.toDouble())
    }

    /** The match must end on its own (winner / time-limit tie-break), then summary, rewards, lobby and a clean log. */
    protected fun finishMatch(ctx: Ctx, endTimeoutMs: Long = 60_000, actorWins: Boolean = true) {
        val ended = awaitNoMatch(endTimeoutMs, keepAlive = ctx.bots)
        E2eReport.check(ctx.suite, "the match ends by itself (no admin end)", "RCON astera match list") {
            assertThat(ended).describedAs("match still listed: ${matchLines()}; sidebar=${sidebar(ctx.actor)}").isTrue()
            "match gone"
        }
        if (!ended) {
            matchLines().forEach { rcon("astera match end ${it.id}") }
            awaitNoMatch(20_000)
        }
        checkEnding(ctx.suite, ctx.bots, ctx.chatFrom, ctx.lobbyTitle)
        E2eReport.check(ctx.suite, "the result screen carries the summary and a verdict (winner or time-limit tie-break)", "SystemChat") {
            val chat = ctx.chat()
            assertThat(chat.any { it.contains("Match Summary") }).describedAs("no summary:\n${quote(chat, 40)}").isTrue()
            if (chat.any { it.contains("ended in a draw") }) E2eReport.notes += "${ctx.suite}: the match ended in a draw (time-limit tie)"
            "verdict lines: ${chat.filter { it.contains("VICTORY") || it.contains("DEFEAT") || it.contains("Victory") || it.contains("Ended by") || it.contains("reached Norma") || it.contains("was ahead") }.take(6)}"
        }
        E2eReport.check(ctx.suite, "each player is told their verdict (the winner VICTORY, the other DEFEAT)", "SystemChat") {
            val mine = ctx.chat(ctx.actor).joinToString("\n")
            val theirs = ctx.chat(ctx.idle).joinToString("\n")
            if (actorWins) {
                assertThat(mine).describedAs("objective actor was not told VICTORY:\n${quote(ctx.chat(ctx.actor), 30)}").containsIgnoringCase("VICTORY")
                assertThat(theirs).describedAs("idle bot was not told DEFEAT:\n${quote(ctx.chat(ctx.idle), 30)}").containsIgnoringCase("DEFEAT")
            } else {
                // Bot-vs-bot fight: either side may win, a draw is possible; each must still be shown a verdict.
                listOf(mine, theirs).forEach { assertThat(it).containsPattern("(?i)VICTORY|DEFEAT|DRAW") }
            }
            "actor verdict=${Regex("(?i)VICTORY|DEFEAT|DRAW").find(mine)?.value}; idle verdict=${Regex("(?i)VICTORY|DEFEAT|DRAW").find(theirs)?.value}"
        }
        E2eReport.check(ctx.suite, "the client received sound packets during the match (objective / combat cues are not dropped)", "ClientboundSound") {
            assertThat(ctx.actor.sounds).describedAs("the actor received no sound packet at all in the whole match").isNotEmpty()
            "actor sounds=${ctx.actor.sounds.size}, last=${ctx.actor.sounds.takeLast(4)}"
        }
        E2eReport.check(ctx.suite, "server log stayed free of errors during the match", "server log") {
            val errs = serverErrorsSince(ctx.logFrom)
            assertThat(errs).describedAs("server errors:\n${quote(errs, 20)}").isEmpty()
            "clean"
        }
    }

    protected fun walkCheck(ctx: Ctx, what: String, result: ObjectiveWalker.Result, x: Double, z: Double) {
        E2eReport.check(ctx.suite, "the bot walked to $what (A* navigation)", "RCON data get Pos") {
            assertThat(result).describedAs("walk to $what ($x, $z) ended $result; pos=${posOf(ctx.actor)}; chat=${ctx.chat().takeLast(4)}").isNotEqualTo(ObjectiveWalker.Result.TIMEOUT)
            "result=$result pos=${posOf(ctx.actor)}"
        }
    }
}
