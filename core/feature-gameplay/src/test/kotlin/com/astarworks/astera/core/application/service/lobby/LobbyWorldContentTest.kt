package com.astarworks.astera.core.application.service.lobby

import com.astarworks.astera.core.application.config.LobbyNpcYamlConfig
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.lobby.LobbyLanePadLayout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Cross-checks the shipped `content/worlds/lobby.yaml` + `content/lobby-npcs/` against the generated plan. */
class LobbyWorldContentTest {

    private val content: Path = locateContentRoot()
    private val config = (LobbyWorldContent.load(content) as Result.Success).value
    private val lanes: List<String> = Regex("""(?m)^\s*-\s*id:\s*(\S+)""")
        .findAll(Files.readString(content.resolve("matchmaking/queue.yaml"))).map { it.groupValues[1] }.toList()
    private val plan = LobbyWorldContent.plan(config, lanes)

    @Test
    fun `lobby yaml loads with its world rules`() {
        assertThat(config.id).isEqualTo("world")
        assertThat(config.markerVersion).isGreaterThanOrEqualTo(1)
        assertThat(config.rules.pvpEnabled).isFalse()
        assertThat(config.rules.buildEnabled).isFalse()
        assertThat(config.rules.mobSpawning).isFalse()
        assertThat(config.rules.timeFrozen).isTrue()
        assertThat(config.rules.timeOfDay).isEqualTo(6000L)
        assertThat(config.rules.voidRescueY).isLessThan(plan.minY)
    }

    @Test
    fun `a missing content directory reports Missing, not a crash`() {
        val result = LobbyWorldContent.load(content.resolve("does-not-exist"))
        assertThat((result as Result.Failure).error).isEqualTo(LobbyWorldError.Missing)
    }

    @Test
    fun `yaml spawn is the plan spawn and stands on solid ground`() {
        val spawn = Vec3(config.spawn.x, config.spawn.y, config.spawn.z)
        assertThat(spawn).isEqualTo(plan.spawn)
        assertThat(config.spawn.yaw).isEqualTo(plan.spawnYaw)
        assertThat(plan.isWalkable(spawn)).isTrue()
    }

    @Test
    fun `every shipped lobby NPC stands on a walkable block of the island plan, in the lobby world`() {
        val npcs = mutableListOf<LobbyNpcYamlConfig>()
        val report = ContentLoader(ContentLoader.DEFAULT_PARSERS, LobbyNpcYamlConfig.serializer())
            .loadFrom(content.resolve("lobby-npcs")) { npcs += it }
        assertThat(report.failures).isEmpty()
        assertThat(npcs).hasSize(8)
        npcs.forEach { npc ->
            assertThat(npc.spawn.world).describedAs(npc.id).isEqualTo(config.id)
            val at = Vec3(npc.spawn.x, npc.spawn.y, npc.spawn.z)
            assertThat(plan.isWalkable(at)).describedAs("${npc.id} at $at").isTrue()
            // content stays in lock-step with the generator's recommended stands
            val spot = plan.npcSpots.getValue(npc.id)
            assertThat(Vec3(spot.x, spot.y, spot.z)).describedAs(npc.id).isEqualTo(at)
            assertThat(spot.yaw).describedAs("${npc.id} yaw").isEqualTo(npc.spawn.yaw)
        }
    }

    @Test
    fun `every shipped queue lane gets a walkable pad that maps back to it`() {
        assertThat(lanes).isNotEmpty()
        assertThat(plan.lanePads.keys).containsExactlyElementsOf(lanes)
        plan.lanePads.forEach { (lane, pad) ->
            assertThat(plan.isWalkable(pad)).describedAs("pad $lane").isTrue()
            assertThat(LobbyLanePadLayout.laneAt(plan.lanePads, config.surfaceY, pad)).isEqualTo(lane)
        }
        assertThat(LobbyLanePadLayout.padCenters(lanes, config.center.x, config.center.z, config.surfaceY))
            .isEqualTo(plan.lanePads)
    }

    @Test
    fun `the training range is part of the lobby build and its spawn is walkable`() {
        assertThat(config.markerVersion).describedAs("training island needs a repaste").isGreaterThanOrEqualTo(3)
        assertThat(plan.training).isEqualTo(
            com.astarworks.astera.core.domain.lobby.LobbyTrainingLayout.plan(config.center.x, config.center.z, config.surfaceY),
        )
        assertThat(plan.isWalkable(plan.training.spawn)).isTrue()
        assertThat(plan.training.region.contains(plan.spawn)).isFalse()
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
