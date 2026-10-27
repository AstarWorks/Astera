package com.astarworks.astera.e2e.live

import com.astarworks.astera.e2e.E2eReport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** `scripts/live-bots.sh chat`: real lobby/game chat relay without changing either backend. */
class ChatLiveTest : LiveBase() {
    private val suite = "Live chat"

    @Test
    fun `chat and screen titles cross backends once`() {
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
        val inputs = listOf("konnnitiha", "hello https://youtu.be/x", "<red>x</red>")
        for (input in inputs) {
            a.say(input)
            b.waitUntil(10_000) { b.chat.drop(fromB).any { input in it.text } }
            Thread.sleep(2_000) // Observe delayed Redis echoes as well as the first delivery.
        }
        val receivedA = a.chat.drop(fromA).map { it.text }
        val receivedB = b.chat.drop(fromB).map { it.text }
        val prefix = "[lobby] [Member] ${a.name} » "
        E2eReport.notes += "Live chat transcript A (${a.name}, lobby): ${receivedA.joinToString(" | ")}"
        E2eReport.notes += "Live chat transcript B (${b.name}, game): ${receivedB.joinToString(" | ")}"
        if (receivedA.any { "chat.disabled.missingProfileKey" in it }) {
            E2eReport.notes += "Unsigned chat rejected by the backend before plugin:chat (chat.disabled.missingProfileKey); conversion, prefix and deduplication remain unverified."
        }
        for (input in inputs) {
            val remote = receivedB.filter { a.name in it && input in it }
            E2eReport.check(suite, "B receives '$input' once with lobby/member prefix and correct text", "SystemChat / PlayerChat") {
                assertThat(remote).describedAs("B transcript: $receivedB").hasSize(1)
                assertThat(remote.single()).startsWith(prefix)
                val body = remote.single().removePrefix(prefix)
                if (input == "konnnitiha") {
                    assertThat(body).matches("(?:こんにちは|[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]*[\\p{IsHan}][\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]*) \\(konnnitiha\\)")
                } else if (input.startsWith('<')) {
                    // Japanize may prepend converted text; the original tags must still be visible literally.
                    assertThat(body).contains(input)
                } else {
                    assertThat(body).isEqualTo(input)
                }
                "B: ${remote.single()}"
            }
            E2eReport.check(suite, "A sees its own '$input' exactly once", "SystemChat / PlayerChat") {
                val own = receivedA.filter { a.name in it && input in it }
                assertThat(own).describedAs("A transcript: $receivedA").hasSize(1)
                assertThat(own).isEqualTo(remote)
                "A: ${own.single()}"
            }
        }

        val titleFrom = b.titles.size
        val titlePackets = b.packetCounts["ClientboundSetTitleTextPacket"]?.get() ?: 0
        val subtitlePackets = b.packetCounts["ClientboundSetSubtitleTextPacket"]?.get() ?: 0
        val gameBefore = rcon("game", "list")
        val reply = rcon("lobby", "astera chat title all Test | Sub")
        b.waitUntil(10_000) { b.titles.drop(titleFrom).map { it.text }.containsAll(listOf("Test", "Sub")) }
        E2eReport.check(suite, "lobby console title all delivers Test and Sub packets to B on game", "SetTitleText + SetSubtitleText") {
            val texts = b.titles.drop(titleFrom).map { it.text }
            val gameAfter = rcon("game", "list")
            assertThat(gameBefore).contains(b.name)
            assertThat(gameAfter).contains(b.name)
            assertThat(b.joined && b.endpoint == LiveEnv.game).isTrue()
            assertThat(texts).describedAs("RCON: $reply; B titles: $texts").contains("Test", "Sub")
            assertThat(b.packetCounts["ClientboundSetTitleTextPacket"]?.get() ?: 0).isGreaterThan(titlePackets)
            assertThat(b.packetCounts["ClientboundSetSubtitleTextPacket"]?.get() ?: 0).isGreaterThan(subtitlePackets)
            "RCON lobby: $reply\nB game title/subtitle: $texts\ngame before: $gameBefore\ngame after: $gameAfter"
        }
    }
}
