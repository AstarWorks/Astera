package com.astarworks.astera.e2e.live

import com.astarworks.astera.e2e.E2eReport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** `scripts/live-bots.sh chat`: real lobby/game chat relay without changing either backend. */
class ChatLiveTest : LiveBase() {
    private val suite = "Live chat"

    @Test
    fun `chat and moment announcements cross backends once`() {
        val a = bot("chata")
        // Ignore lobby-return Connect requests: game rejects players without a match assignment.
        // Pin only this probe to game so it can observe Redis delivery; match/transfer behaviour is out of scope.
        val b = bot("chatb", servers = mapOf("game" to LiveEnv.game))
        // This checkout's live harness emulates Velocity's backend switch; it does not traverse the proxy.
        b.forceHop("game")
        val ready = b.waitUntil(30_000) { b.joined && b.endpoint == LiveEnv.game && b.commandTree != null }
        E2eReport.check(suite, "A remains on lobby and B moves to game", "forwarded backend login + RCON list") {
            assertThat(ready).describedAs("B disconnect=${b.disconnectReason}").isTrue()
            assertThat(a.joined && a.endpoint == LiveEnv.lobby).isTrue()
            val lobby = rcon("lobby", "list")
            val game = rcon("game", "list")
            assertThat(lobby).contains(a.name).doesNotContain(b.name)
            assertThat(game).contains(b.name).doesNotContain(a.name)
            "lobby: $lobby\ngame: $game\nB hops=${b.hops}; direct backend forwarding, proxy traversal unverified"
        }
        if (!ready || !a.joined) return

        val fromA = a.chat.size
        val fromB = b.chat.size
        val prefix = "[lobby] [Member] ${a.name} » "
        fun matchesInput(line: String, input: String): Boolean {
            if (!line.startsWith(prefix)) return false
            val body = line.removePrefix(prefix)
            return body == input || body.endsWith(" ($input)")
        }
        val inputs = listOf("konnnitiha", "konnnitiha red", "red", "&cX", "&#12abefX", "# {name} {message}", "hello https://youtu.be/x", "<red>x</red>")
        for (input in inputs) {
            a.say(input)
            b.waitUntil(10_000) { b.chat.drop(fromB).any { matchesInput(it.text, input) } }
            Thread.sleep(2_000) // Observe delayed Redis echoes as well as the first delivery.
        }
        val receivedA = a.chat.drop(fromA).map { it.text }
        val receivedB = b.chat.drop(fromB).map { it.text }
        E2eReport.notes += "Live chat transcript A (${a.name}, lobby): ${receivedA.joinToString(" | ")}"
        E2eReport.notes += "Live chat transcript B (${b.name}, game): ${receivedB.joinToString(" | ")}"
        if (receivedA.any { "chat.disabled.missingProfileKey" in it }) {
            E2eReport.notes += "Unsigned chat rejected by the backend before plugin:chat (chat.disabled.missingProfileKey); conversion, prefix and deduplication remain unverified."
        }
        for (input in inputs) {
            val remote = receivedB.filter { matchesInput(it, input) }
            E2eReport.check(suite, "B receives '$input' once with lobby/member prefix and correct text", "SystemChat / PlayerChat") {
                assertThat(remote).describedAs("B transcript: $receivedB").hasSize(1)
                assertThat(remote.single()).startsWith(prefix)
                val body = remote.single().removePrefix(prefix)
                if (input == "konnnitiha" || input == "konnnitiha red") {
                    val tail = if (input.endsWith(" red")) " red" else ""
                    assertThat(body).matches("(?:こんにちは|[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]*[\\p{IsHan}][\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]*)$tail \\(${Regex.escape(input)}\\)")
                } else {
                    // Non-romaji words, MiniMessage tags, placeholders and member & codes stay literal.
                    assertThat(body).isEqualTo(input)
                }
                "B: ${remote.single()}"
            }
            E2eReport.check(suite, "A sees its own '$input' exactly once", "SystemChat / PlayerChat") {
                val own = receivedA.filter { matchesInput(it, input) }
                assertThat(own).describedAs("A transcript: $receivedA").hasSize(1)
                assertThat(own).isEqualTo(remote)
                "A: ${own.single()}"
            }
        }

        val chatFrom = b.chat.size
        val titleFrom = b.titles.size
        val reply = rcon("lobby", "astera announce rare-drop all name=${a.name} item=AnnounceProbe")
        b.waitUntil(10_000) { b.chat.drop(chatFrom).any { "AnnounceProbe" in it.text } }
        E2eReport.check(suite, "rare-drop reaches game with chat and title", "SystemChat + SetTitleText") {
            val lines = b.chat.drop(chatFrom).filter { "AnnounceProbe" in it.text }
            assertThat(lines).describedAs("RCON: $reply").hasSize(1)
            assertThat(lines.single().text).contains(a.name)
            assertThat(b.titles.drop(titleFrom)).isNotEmpty()
            "RCON lobby: $reply; game: ${lines.single().text}"
        }
    }
}
