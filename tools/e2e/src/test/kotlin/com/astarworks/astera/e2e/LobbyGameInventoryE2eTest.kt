package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Founder 2026-10-30: 「ロビーは上書きされない。ただ、ゲームは上書きされる」 - an item the player holds in the lobby is
 * gone after the sync onto the game server (PlayerHandoffService.applySnapshot or a later writer).
 *
 * TOPOLOGY LIMIT: this e2e stack is ONE Paper backend (ASTERA_SERVER_ROLE=all, in-memory snapshots, no Postgres, no
 * proxy messaging; the handoff service runs with awaitToken=false). There is no separate lobby/game server, so the
 * token-wait / timeout path cannot be reproduced here. The closest real path on this stack is the player leaving and
 * logging in again through Velocity: onQuit captures + saves the snapshot, onJoin loads + applies it
 * (applySnapshot -> gateway overwrite), then the lobby kit / match compass writers run. The test samples the probe
 * item at 1 s, 3 s and 6 s after each arrival (a late async overwrite shows up in the later samples) and, on failure,
 * prints every inventory-writer line (commit 72c87684c) the server logged for the bot, which names the culprit.
 */
class LobbyGameInventoryE2eTest : E2eBase() {

    private val suite = "Lobby -> game inventory"
    private val probeSlot = 3

    private fun hasProbe(name: String): Boolean =
        rcon("execute if items entity $name hotbar.$probeSlot minecraft:stick").startsWith("Test passed")

    private fun writerLines(name: String, fromLine: Int): String =
        ServerLog.read().lineSequence().drop(fromLine)
            .filter { line ->
                name in line && listOf("Inventory overwrite", "Inventory capture", "Inventory write", "snapshot", "Hand-off", "restore")
                    .any { it in line }
            }
            .joinToString("\n").ifEmpty { "(no inventory-writer lines for $name)" }

    private fun sample(name: String, fromLine: Int, label: String): String {
        val seen = mutableListOf<String>()
        var lastT = 0L
        for (t in listOf(1_000L, 3_000L, 6_000L)) {
            Thread.sleep(t - lastT)
            lastT = t
            val ok = hasProbe(name)
            seen += "${t / 1000}s=${if (ok) "present" else "LOST"}"
            assertThat(ok)
                .describedAs(
                    "$label: probe item in hotbar.$probeSlot lost at ${t / 1000}s after arrival (${seen.joinToString()}); " +
                        "inventory: ${rcon("data get entity $name Inventory").take(400)}\nserver writers:\n${writerLines(name, fromLine)}",
                )
                .isTrue()
        }
        return seen.joinToString()
    }

    @Test
    fun `a lobby item survives the sync onto the game side`() {
        val logFrom = ServerLog.lineCount()
        val first = bot("InvHand", PackPolicy.DECLINE)
        val name = first.name
        first.waitUntil(15_000) { first.sidebarLines().isNotEmpty() && first.hotbar()[8] != -1 }
        Thread.sleep(1_500)
        rcon("item replace entity $name hotbar.$probeSlot with minecraft:stick[custom_name='\"HandoffProbe\"']")

        E2eReport.check(suite, "probe item given in the lobby stays put for 6 s", "RCON execute if items + server log") {
            assertThat(hasProbe(name)).describedAs("item missing right after give").isTrue()
            sample(name, logFrom, "lobby")
        }

        // Leave (onQuit capture + save) and arrive again through Velocity (onJoin load + apply + arrival writers).
        first.close()
        Thread.sleep(2_000)
        val arrivalLog = ServerLog.lineCount()
        val back = bot("InvHand", PackPolicy.DECLINE, exactName = name)
        back.waitUntil(15_000) { back.sidebarLines().isNotEmpty() && back.hotbar()[8] != -1 }

        E2eReport.check(suite, "probe item is still in the same slot 1 s / 3 s / 6 s after arriving again", "RCON execute if items + server log") {
            sample(name, arrivalLog, "after arrival")
        }
    }
}
