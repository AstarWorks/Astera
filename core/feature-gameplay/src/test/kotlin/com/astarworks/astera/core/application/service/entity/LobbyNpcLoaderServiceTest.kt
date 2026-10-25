package com.astarworks.astera.core.application.service.entity

import com.astarworks.astera.core.application.port.outbound.entity.INpcBodyMaterializer
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.NpcInteractionClick
import com.astarworks.astera.core.domain.entity.SpawnSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeEntitySpawner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LobbyNpcLoaderServiceTest {

    private class RecordingBodies : INpcBodyMaterializer {
        data class Body(val handle: EntityHandle, val world: WorldId, val position: Vec3, val yaw: Float, val name: MessageKey)

        val bodies = mutableListOf<Body>()
        override fun materialize(handle: EntityHandle, world: WorldId, position: Vec3, yaw: Float, pitch: Float, displayName: MessageKey) {
            bodies += Body(handle, world, position, yaw, displayName)
        }

        override fun removeAll() = bodies.clear()
    }

    private lateinit var spawner: FakeEntitySpawner
    private lateinit var router: InMemoryNpcInteractionRouter
    private lateinit var bodies: RecordingBodies
    private lateinit var loader: LobbyNpcLoaderService

    @BeforeEach
    fun setUp() {
        spawner = FakeEntitySpawner()
        router = InMemoryNpcInteractionRouter()
        bodies = RecordingBodies()
        loader = LobbyNpcLoaderService(spawner, router, bodies)
    }

    @Test
    fun `the shipped shop NPC spawns in the lobby and its right click opens the shop`() {
        val report = loader.loadFrom(locateContentRoot().resolve("lobby-npcs"))

        assertThat(report.failures).isEmpty()
        val handle = loader.spawned.getValue("shop-npc")
        val record = spawner.spawned.single { it.handle == handle }
        assertThat(record.world).isEqualTo(WorldId("lobby"))
        assertThat(record.spec).isInstanceOf(SpawnSpec.DisplayNpc::class.java)
        assertThat(router.resolve(handle, NpcInteractionClick.RIGHT)).isEqualTo(GuiIntent("lobby.shop.open"))
        assertThat(bodies.bodies.single { it.handle == handle }.name)
            .isEqualTo(MessageKey("astera.lobby.npc.shop.name"))
    }

    @Test
    fun `every shipped lobby NPC loads and gets a body`() {
        val dir = locateContentRoot().resolve("lobby-npcs")
        val fileCount = Files.list(dir).use { s -> s.filter { it.toString().endsWith(".yaml") }.count().toInt() }

        val report = loader.loadFrom(dir)

        assertThat(report.failed).isZero
        assertThat(report.loaded).isEqualTo(fileCount)
        assertThat(bodies.bodies).hasSize(fileCount)
    }

    @Test
    fun `a bad file is reported and does not stop the others`(@TempDir dir: Path) {
        Files.writeString(
            dir.resolve("good.yaml"),
            """
            id: good-npc
            display_name: astera.lobby.npc.shop.name
            spawn: { world: lobby, x: 1.0, y: 64.0, z: 2.0 }
            interaction: { right_click_intent: lobby.shop.open }
            """.trimIndent(),
        )
        Files.writeString(
            dir.resolve("bad.yaml"),
            """
            id: bad-npc
            display_name: astera.lobby.npc.shop.name
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
    fun `no body materializer still spawns the logical NPC and binds the intent`() {
        val headless = LobbyNpcLoaderService(spawner, router, bodies = null)

        headless.loadFrom(locateContentRoot().resolve("lobby-npcs"))

        val handle = headless.spawned.getValue("shop-npc")
        assertThat(router.resolve(handle, NpcInteractionClick.RIGHT)).isEqualTo(GuiIntent("lobby.shop.open"))
        assertThat(bodies.bodies).isEmpty()
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
