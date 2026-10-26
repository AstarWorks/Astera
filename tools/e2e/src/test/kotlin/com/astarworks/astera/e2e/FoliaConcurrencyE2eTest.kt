package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** N simultaneous real matches plus an independent lobby, on fresh test-only stage copies. */
class FoliaConcurrencyE2eTest : PlaythroughBase() {
    private val suite = "Folia concurrency"

    @Test
    fun `independent regions finish N matches while the lobby stays playable`() {
        val n = System.getProperty("astera.e2e.concurrentMatches", "4").toInt()
        require(n > 0)
        val lobby = bot("FlLobby", PackPolicy.DECLINE)
        val pairs = (1..n).map { i -> (1..2).map { bot("Fl${i}p$it", PackPolicy.DECLINE) } }
        val all = listOf(lobby) + pairs.flatten()
        val chatFrom = all.associateWith { it.chat.size }
        // Full two-player lanes start immediately; issue all joins without awaiting any match.
        pairs.forEachIndexed { i, pair -> pair.forEach { it.command("astera queue join folia-e2e-${i + 1}") } }
        var active = emptyList<MatchLine>()
        lobby.waitUntil(70_000) {
            all.forEach { it.nudge() }
            active = matchLines().filter { it.phase == "ACTIVE" && "folia-e2e-" in it.raw }
            active.size == n
        }
        E2eReport.check(suite, "all $n matches are ACTIVE together in distinct slots", "RCON match list") {
            assertThat(active).describedAs("list=${matchLines()}; chats=${pairs.map { it.first().chatTexts().takeLast(5) }}").hasSize(n)
            assertThat(active.map { originOf(it) }).doesNotContainNull().doesNotHaveDuplicates()
            active.joinToString("\n") { it.raw }
        }

        // Thread names are diagnostic only: Folia can move one region between workers.
        // Ownership of every other player's loaded location is the actual separation proof.
        val token = "probe${System.nanoTime()}"
        rcon("e2eregions $token")
        var samples = emptyList<String>()
        lobby.waitUntil(15_000) {
            samples = ServerLog.lines("FOLIA_E2E_REGION $token")
            samples.size >= all.size
        }
        E2eReport.notes += samples
        pairs.forEachIndexed { i, pair ->
            E2eReport.check(suite, "match ${i + 1} owns only its pair; lobby is a separate Folia region", "owner-thread probe + client positions") {
                assertThat(samples).hasSize(all.size)
                pair.forEach { player ->
                    val sample = samples.single { "player=${player.name} " in it }
                    assertThat(sample.substringAfter(" owns=").split(",").toSet())
                        .describedAs(sample).isEqualTo(pair.map { it.name }.toSet())
                }
                val a = pair.first()
                val slot = active.singleOrNull { line ->
                    originOf(line)?.let { (x, z) -> kotlin.math.abs(a.x - x) < 512 && kotlin.math.abs(a.z - z) < 512 } == true
                }
                assertThat(slot).describedAs("pair outside active slot: ${pair.map { Triple(it.x, it.y, it.z) }}").isNotNull()
                samples.filter { line -> pair.any { "player=${it.name} " in line } }.joinToString("\n")
            }
        }
        E2eReport.check(suite, "lobby remains separate and responds during concurrent matches", "owner-thread probe + command reply") {
            val sample = samples.single { "player=${lobby.name} " in it }
            assertThat(sample.substringAfter(" owns=")).isEqualTo(lobby.name)
            val from = lobby.chat.size
            lobby.command("astera queue list")
            assertThat(lobby.chatSince(from)).isNotEmpty()
            sample
        }

        // All bots deliberately stay idle: no stars, kills or objectives -> a deterministic tied result.
        val ended = awaitNoMatch(100_000, keepAlive = all)
        pairs.forEachIndexed { i, pair ->
            E2eReport.check(suite, "match ${i + 1} finishes naturally with the correct tied result for both players", "SystemChat + match list") {
                assertThat(ended).describedAs("still running: ${matchLines()}").isTrue()
                pair.forEach { b -> b.waitUntil(10_000) { b.chat.drop(chatFrom.getValue(b)).any { "all tied" in it.text } } }
                val chats = pair.map { b -> b.chat.drop(chatFrom.getValue(b)).map { clean(it.text) } }
                chats.forEach { chat ->
                    assertThat(chat.count { "Match Summary" in it }).isEqualTo(1)
                    assertThat(chat).anyMatch { "all tied" in it }
                    assertThat(chat).anyMatch { it.startsWith("Score") && Regex("(Red|Blue)\\D*0").findAll(it).count() == 2 }
                    assertThat(chat).noneMatch { "VICTORY" in it || "DEFEAT" in it || "administrator" in it }
                    assertThat(rawKeys(chat)).isEmpty()
                }
                assertThat(chats[0].filter { it.startsWith("Score") }).isEqualTo(chats[1].filter { it.startsWith("Score") })
                chats.joinToString("\n") { quote(it, 25) }
            }
        }
        E2eReport.check(suite, "server log has no ownership exceptions", "entire server log") {
            val log = ServerLog.read()
            assertThat(log).isNotBlank()
            val errors = log.lineSequence().filter {
                it.contains("Thread failed main thread check", true) ||
                    it.contains("not owned by current region", true) ||
                    (it.contains("IllegalStateException") && Regex("own|region|thread", RegexOption.IGNORE_CASE).containsMatchIn(it))
            }.toList()
            assertThat(errors).describedAs(quote(errors)).isEmpty()
            "no ownership errors; $n matches requested"
        }
    }
}
