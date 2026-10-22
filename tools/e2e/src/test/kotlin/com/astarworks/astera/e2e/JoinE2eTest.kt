package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.geysermc.mcprotocollib.protocol.data.game.ResourcePackStatus
import org.junit.jupiter.api.Test

class JoinE2eTest : E2eBase() {

    private val suite = "Join"

    @Test
    fun `join with resource pack loaded`() {
        val b = bot("Joiner", PackPolicy.LOAD)
        // Give the server a few seconds to push the pack, sidebar, HUD.
        b.waitUntil(8_000) { b.packPushes.isNotEmpty() && b.sidebarLines().isNotEmpty() && b.actionBars.isNotEmpty() }

        E2eReport.check(suite, "bot joins and stays connected (no kick)", "login / disconnect packet") {
            assertThat(b.joined).describedAs("joined; disconnect=${b.disconnectReason}").isTrue()
            assertThat(b.disconnectReason).describedAs("disconnect reason").isNull()
            "joined as ${b.name}; world=${b.worldName}; pos=(${b.x.toInt()},${b.y.toInt()},${b.z.toInt()}); packets=${b.packetCounts.keys.size} kinds"
        }

        E2eReport.check(suite, "resource pack push carries URL + SHA-1", "ClientboundResourcePackPush") {
            assertThat(b.packPushes).describedAs("resource pack pushes").isNotEmpty()
            val p = b.packPushes.first()
            assertThat(p.url).startsWith("http")
            assertThat(p.hash).matches("[0-9a-f]{40}")
            "url=${p.url} sha1=${p.hash} required=${p.required} prompt=${p.prompt}"
        }

        E2eReport.check(suite, "server reacts to pack statuses without kicking (LOAD)", "ServerboundResourcePack -> server") {
            assertThat(b.packStatusesSent).containsExactly(
                ResourcePackStatus.ACCEPTED, ResourcePackStatus.DOWNLOADED, ResourcePackStatus.SUCCESSFULLY_LOADED,
            )
            Thread.sleep(1_500)
            assertThat(b.disconnectReason).isNull()
            "sent ${b.packStatusesSent}; still connected: ${b.joined}"
        }

        E2eReport.check(suite, "lobby sidebar objective + lines", "SetObjective/SetDisplayObjective/SetScore/SetPlayerTeam") {
            assertThat(b.sidebarTitle()).describedAs("sidebar objective title (objectives=${b.objectives} slots=${b.displaySlots})").isNotNull()
            val lines = b.sidebarLines()
            assertThat(lines).describedAs("sidebar lines").isNotEmpty()
            assertThat(lines.joinToString("")).describedAs("raw i18n keys leaked into sidebar").doesNotContainPattern("astera\\.[a-z-]+\\.[a-z.-]+")
            "title='${b.sidebarTitle()}'\n${quote(lines)}"
        }

        E2eReport.check(suite, "action bar shows the HP status line", "ClientboundSetActionBarText / SystemChat(overlay)") {
            assertThat(b.actionBars).describedAs("no action bar message received at all").isNotEmpty()
            val history = b.actionBars.map { visible(it.text) }
            val last = b.actionBars.last().text
            assertThat(last).describedAs("last action bar text is blank (HP line cleared or never sent); history=$history").isNotBlank()
            assertThat(last).describedAs("action bar must show an HP figure (digits) or the HP glyph bar").containsPattern("\\d|[\uE000-\uF8FF]")
            "count=${b.actionBars.size}; last='${visible(last)}'; history=$history"
        }

        E2eReport.check(suite, "no raw i18n keys in join-time chat", "SystemChat") {
            val raw = b.chatTexts().filter { Regex("<astera\\.[a-z0-9_.-]+>").containsMatchIn(it) }
            assertThat(raw).describedAs("chat lines that are raw translation keys").isEmpty()
            "chat lines at join:\n${quote(b.chatTexts())}"
        }
    }

    @Test
    fun `join with resource pack declined`() {
        val b = bot("JoinDecl", PackPolicy.DECLINE)
        b.waitUntil(6_000) { b.actionBars.isNotEmpty() }
        Thread.sleep(2_000)
        E2eReport.check(suite, "declining the pack is tolerated (pack not required)", "ServerboundResourcePack(DECLINED)") {
            assertThat(b.packPushes).isNotEmpty()
            assertThat(b.packStatusesSent).containsExactly(ResourcePackStatus.DECLINED)
            assertThat(b.disconnectReason).describedAs("kicked after decline").isNull()
            "pushes=${b.packPushes.size}; sent=${b.packStatusesSent}; connected=${b.joined}"
        }
        E2eReport.check(suite, "text-fallback HP line is shown without the pack", "action bar") {
            assertThat(b.actionBars).isNotEmpty()
            "last='${b.actionBars.last().text}'"
        }
    }

    @Test
    fun `join and fail the pack download`() {
        val b = bot("JoinFail", PackPolicy.FAIL_DOWNLOAD)
        Thread.sleep(3_000)
        E2eReport.check(suite, "failed pack download is tolerated", "ServerboundResourcePack(FAILED_DOWNLOAD)") {
            assertThat(b.packStatusesSent).containsExactly(ResourcePackStatus.ACCEPTED, ResourcePackStatus.FAILED_DOWNLOAD)
            assertThat(b.disconnectReason).describedAs("kicked after failed download").isNull()
            "sent=${b.packStatusesSent}; connected=${b.joined}"
        }
    }
}
