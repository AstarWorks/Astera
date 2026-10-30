package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `astera admin reload <category>` on a real Paper server for the categories that were not reloadable before
 * (docs/vision/areas: economy-life, ui-access, builds-maps): shops, lobby-npcs, worlds, balance-core, glow.
 * The shipped content is unchanged, so each reload must succeed, say what it did, and leave live state
 * untouched (the player's wallet and the lobby NPC bodies are not reset by a no-op reload).
 */
class ContentReloadE2eTest : E2eBase() {

    private val suite = "Content reload"

    private fun plain(s: String): String = s.replace(Regex("§."), "")

    private fun reload(category: String): String = plain(rcon("astera admin reload $category"))

    private fun gold(name: String): Long =
        Regex("GOLD:\\s*(\\d+)").find(plain(rcon("astera economy balance gold $name")))?.groupValues?.get(1)?.toLong() ?: -1L

    @Test
    fun `every previously frozen category reloads from disk and reports what it did`() {
        val player = bot("ReloadPlayer")
        check(player.waitUntil(15_000) { gold(player.name) >= 150L }) { "the starter GOLD never reached ${player.name}" }
        val walletBefore = gold(player.name)

        E2eReport.check(suite, "shops reload diff-applies and keeps stock", "RCON astera admin reload shops") {
            val out = reload("shops")
            assertThat(out).contains("shops").doesNotContainIgnoringCase("failed")
            assertThat(out).contains("shop example-shop")
            out
        }
        E2eReport.check(suite, "lobby-npcs reload keeps every untouched NPC", "RCON astera admin reload lobby-npcs") {
            val out = reload("lobby-npcs")
            assertThat(out).contains("lobby NPCs: +0 spawned, -0 despawned, ~0 respawned")
            out
        }
        E2eReport.check(suite, "worlds reload is a no-op on unchanged lobby.yaml", "RCON astera admin reload worlds") {
            val out = reload("worlds")
            assertThat(out).contains("lobby world: unchanged").doesNotContain("RESTART_REQUIRED")
            out
        }
        E2eReport.check(suite, "balance-core reload swaps live refs", "RCON astera admin reload balance-core") {
            val out = reload("balance-core")
            assertThat(out).contains("balance-core").contains("next tick").doesNotContainIgnoringCase("failed")
            out
        }
        E2eReport.check(suite, "glow reload reads the profile table", "RCON astera admin reload glow") {
            val out = reload("glow")
            assertThat(out).contains("glow").doesNotContainIgnoringCase("failed")
            out
        }
        E2eReport.check(suite, "no-op reloads do not touch the wallet", "RCON astera economy balance gold") {
            assertThat(gold(player.name)).isEqualTo(walletBefore)
            "GOLD before=$walletBefore after=${gold(player.name)}"
        }
    }
}
