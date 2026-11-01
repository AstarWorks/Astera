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

    /**
     * A real edit, not a no-op: a changed copy of one shop and one lobby NPC is written into the server's content
     * dir (the shops / lobby-npcs volumes shared with the server, docker-compose.e2e.yml; the runner's own checkout is NOT what the server reads), reloaded, asserted through the reload output, then the originals are restored and reloaded again.
     */
    @Test
    fun `a real edit of a shop and a lobby NPC is applied and then restored`() {
        val dir = java.io.File(System.getProperty("astera.e2e.serverContentDir", "/e2e-content"))
        val shop = dir.resolve("shops/example-shop.yaml")
        val npc = dir.resolve("lobby-npcs/market-npc.yaml")
        val shopOriginal = shop.readText()
        val npcOriginal = npc.readText()
        try {
            E2eReport.check(suite, "an edited shop gains the new offer", "write shops/example-shop.yaml + reload shops") {
                val probe = "  - id: reload-probe\n    item: dagger\n    price:\n      currency: GOLD\n      amount: 151\n" +
                    "    stock:\n      type: unlimited\n    display_key: astera.economy.shop.example-shop.offer.dagger\n"
                shop.writeText(shopOriginal.trimEnd('\n') + "\n" + probe)
                val out = reload("shops")
                assertThat(out).contains("shop example-shop: +1 offers, -0 offers").doesNotContainIgnoringCase("failed")
                out
            }
            E2eReport.check(suite, "an edited lobby NPC is respawned", "write lobby-npcs/market-npc.yaml + reload lobby-npcs") {
                npc.writeText(npcOriginal.replace(Regex("(?m)^  x: 47\\.5$"), "  x: 48.5"))
                check(npc.readText() != npcOriginal) { "market-npc.yaml spawn x changed upstream; update this test" }
                val out = reload("lobby-npcs")
                assertThat(out).contains("lobby NPCs: +0 spawned, -0 despawned, ~1 respawned")
                out
            }
        } finally {
            shop.writeText(shopOriginal)
            npc.writeText(npcOriginal)
        }
        E2eReport.check(suite, "restoring the originals reverts both", "restore files + reload shops, lobby-npcs") {
            val shops = reload("shops")
            val npcs = reload("lobby-npcs")
            assertThat(shops).contains("shop example-shop: +0 offers, -1 offers")
            assertThat(npcs).contains("~1 respawned")
            "$shops | $npcs"
        }
    }
}
