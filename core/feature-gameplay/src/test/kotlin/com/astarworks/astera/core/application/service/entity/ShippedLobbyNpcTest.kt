package com.astarworks.astera.core.application.service.entity

import com.astarworks.astera.core.domain.entity.NpcInteractionClick
import com.astarworks.astera.core.domain.gui.GuiIntent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ShippedLobbyNpcTest {

    private val npcAdapter = NpcAdapter()
    private val router = InMemoryNpcInteractionRouter()
    private val spawner = RouterEntitySpawner(npcAdapter,
        com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus(),
        com.astarworks.astera.testfixtures.FakeScheduler(), InMemoryEntityRegistry(), router)
    private val loader = LobbyNpcLoaderService(spawner, router)

    @Test
    fun `the shipped market NPC spawns in the lobby and its right click opens the shop`() {
        val report = loader.loadFrom(locateContentRoot().resolve("lobby-npcs"))

        assertThat(report.failures).isEmpty()
        val handle = loader.spawned.getValue("market-npc")
        assertThat(npcAdapter.all()).contains(handle)
        assertThat(router.resolve(handle, NpcInteractionClick.RIGHT)).isEqualTo(GuiIntent("lobby.shop.open"))
    }

    @Test
    fun `every shipped lobby NPC loads uses the single NPC backend`() {
        val dir = locateContentRoot().resolve("lobby-npcs")
        val fileCount = Files.list(dir).use { s -> s.filter { it.toString().endsWith(".yaml") }.count().toInt() }

        val report = loader.loadFrom(dir)

        assertThat(report.failed).isZero
        assertThat(report.loaded).isEqualTo(fileCount)
        assertThat(npcAdapter.all()).hasSize(fileCount)
    }

    @Test
    fun `a bad file is reported and does not stop the others`(@TempDir dir: Path) {
        Files.writeString(
            dir.resolve("good.yaml"),
            """
            id: good-npc
            display_name: astera.lobby.npc.market.name
            spawn: { world: lobby, x: 1.0, y: 64.0, z: 2.0 }
            interaction: { right_click_intent: lobby.shop.open }
            """.trimIndent(),
        )
        Files.writeString(
            dir.resolve("bad.yaml"),
            """
            id: bad-npc
            display_name: astera.lobby.npc.market.name
            type: citizens
            spawn: { world: lobby, x: 1.0, y: 64.0, z: 2.0 }
            """.trimIndent(),
        )

        val report = loader.loadFrom(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(loader.spawned.keys).containsExactly("good-npc")
    }

    @Test
    fun `headless runtime spawns the logical NPC and binds the intent`() {
        val headless = LobbyNpcLoaderService(spawner, router)

        headless.loadFrom(locateContentRoot().resolve("lobby-npcs"))

        val handle = headless.spawned.getValue("market-npc")
        assertThat(router.resolve(handle, NpcInteractionClick.RIGHT)).isEqualTo(GuiIntent("lobby.shop.open"))
        assertThat(npcAdapter.all()).contains(handle)
    }

    private fun locateContentRoot(): Path {
        var cur: Path? = Path.of("").toAbsolutePath()
        while (cur != null) {
            if (Files.isRegularFile(cur.resolve("settings.gradle.kts")) && Files.isDirectory(cur.resolve("content"))) {
                return cur.resolve("content")
            }
            cur = cur.parent
        }
        error("repo root (settings.gradle.kts + content/) not found")
    }
}
