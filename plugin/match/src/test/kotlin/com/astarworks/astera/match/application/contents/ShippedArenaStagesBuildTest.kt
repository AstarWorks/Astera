package com.astarworks.astera.match.application.contents

import com.astarworks.astera.core.domain.stage.ArenaIslandGenerator
import com.astarworks.astera.core.domain.stage.ArenaLandmarkKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.floor

/**
 * ADR-0246: every shipped stage is a sky-island arena, and the real `content/stages` + `content/generators`
 * build into a consistent plan: spawns stand on an island at `surface_y + 1`, objective anchors sit at the right
 * height on an island, the kill plane is below the build, generators stand on a landmark pedestal.
 */
class ShippedArenaStagesBuildTest {

    private val contentRoot: Path = locateContentRoot()

    private fun stages(): List<com.astarworks.astera.core.domain.stage.Stage> {
        val contents = StageContents()
        val report = contents.bootstrap(contentRoot.resolve("stages"))
        assertThat(report.failures).isEmpty()
        return contents.all().toList()
    }

    @Test
    fun `every shipped stage has a sky-island layout that builds without problems`() {
        val stages = stages()
        assertThat(stages).isNotEmpty
        for (stage in stages) {
            val layout = stage.layout
            assertThat(layout).describedAs("stage '${stage.id.value}' has no layout (a stage nobody can build is dead content)").isNotNull
            val plan = ArenaIslandGenerator.generate(stage)
            assertThat(plan.problems).describedAs("stage '${stage.id.value}'").isEmpty()
            assertThat(layout!!.killY).describedAs("kill plane below the build of '${stage.id.value}'").isLessThan(plan.minY)
            assertThat(stage.world.value).describedAs("stage '${stage.id.value}' world").isEqualTo("world")
        }
    }

    @Test
    fun `shipped plans fit the reserved slot footprint`() {
        for (stage in stages()) {
            val plan = ArenaIslandGenerator.generate(stage)
            assertThat(plan.minX).isGreaterThan(-768)
            assertThat(plan.maxX).isLessThan(768)
            assertThat(plan.minZ).isGreaterThan(-768)
            assertThat(plan.maxZ).isLessThan(768)
        }
    }

    @Test
    fun `every shipped generator stands on a landmark pedestal of its stage`() {
        val stages = stages().associateBy { it.id.value }
        val generators = GeneratorContents()
        assertThat(generators.bootstrap(contentRoot.resolve("generators")).failures).isEmpty()
        for (generator in generators.all()) {
            val stage = stages[generator.matchStageId]
            assertThat(stage).describedAs("generator '${generator.id}' targets an unknown stage '${generator.matchStageId}'").isNotNull
            val layout = stage!!.layout!!
            val x = floor(generator.position.x).toInt()
            val z = floor(generator.position.z).toInt()
            assertThat(layout.landmarks.any { it.kind == ArenaLandmarkKind.GENERATOR && it.x == x && it.z == z })
                .describedAs("generator '${generator.id}' at ($x, $z) has no generator landmark in '${stage.id.value}'")
                .isTrue
            assertThat(floor(generator.position.y).toInt()).describedAs("generator '${generator.id}' height").isEqualTo(layout.surfaceY + 1)
        }
    }

    private fun locateContentRoot(): Path {
        var cur: Path? = Path.of("").toAbsolutePath()
        while (cur != null) {
            if (Files.isRegularFile(cur.resolve("settings.gradle.kts")) && Files.isDirectory(cur.resolve("content"))) {
                return cur.resolve("content")
            }
            cur = cur.parent
        }
        error("repo root (settings.gradle.kts + content/) not found above ${Path.of("").toAbsolutePath()}")
    }
}
