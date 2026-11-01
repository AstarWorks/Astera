package com.astarworks.astera.match.application.contents

import com.astarworks.astera.core.application.service.arena.ArenaImportContent
import com.astarworks.astera.core.application.service.arena.ArenaStructureContent
import com.astarworks.astera.core.domain.stage.ArenaIslandGenerator
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
        val parts = ArenaStructureContent.load(contentRoot)
        val imports = ArenaImportContent.load(contentRoot)
        for (stage in stages) {
            val layout = stage.layout
            assertThat(layout).describedAs("stage '${stage.id.value}' has no layout (a stage nobody can build is dead content)").isNotNull
            val plan = ArenaIslandGenerator.generate(stage, parts, imports)
            assertThat(plan.problems).describedAs("stage '${stage.id.value}'").isEmpty()
            assertThat(layout!!.killY).describedAs("kill plane below the build of '${stage.id.value}'").isLessThan(plan.minY)
            assertThat(stage.world.value).describedAs("stage '${stage.id.value}' world").isEqualTo("world")
        }
    }

    @Test
    fun `every shipped structure file loads through the domain validator`() {
        val files = Files.list(contentRoot.resolve("structures")).use { s -> s.filter { it.toString().endsWith(".yaml") }.toList() }
        assertThat(files).isNotEmpty
        // ArenaStructureContent.load throws when any file fails to decode or fails ArenaStructureBox validation (block IDs, states).
        val parts = ArenaStructureContent.load(contentRoot)
        assertThat(parts.keys).describedAs("a structure file that did not produce a part").hasSize(files.size)
    }

    @Test
    fun `no shipped stage sits in the darkest part of the night or pairs night with rain`() {
        for (stage in stages()) {
            val sky = stage.layout!!.atmosphere ?: continue
            // Vanilla sky light is symmetric around 18000; 12500 (dusk) / 23500 (dawn) stay readable, the span between is pitch dark.
            assertThat(sky.timeOfDay).describedAs("'${stage.id.value}' time_of_day is too dark (BUG #14)")
                .matches { it < 12_600L || it > 23_400L }
            if (sky.timeOfDay > 12_000L) {
                assertThat(sky.weather).describedAs("'${stage.id.value}' night + rain is too dark").isEqualTo(com.astarworks.astera.core.domain.stage.ArenaWeather.CLEAR)
            }
        }
    }

    @Test
    fun `shipped plans fit the reserved slot footprint`() {
        val parts = ArenaStructureContent.load(contentRoot)
        val imports = ArenaImportContent.load(contentRoot)
        for (stage in stages()) {
            val plan = ArenaIslandGenerator.generate(stage, parts, imports)
            assertThat(plan.minX).describedAs("'${stage.id.value}'").isGreaterThan(-512)
            assertThat(plan.maxX).describedAs("'${stage.id.value}'").isLessThan(512)
            assertThat(plan.minZ).describedAs("'${stage.id.value}'").isGreaterThan(-512)
            assertThat(plan.maxZ).describedAs("'${stage.id.value}'").isLessThan(512)
        }
    }

    @Test
    fun `every shipped generator and every candidate star spot stands on the ground of its stage`() {
        val stages = stages().associateBy { it.id.value }
        val parts = ArenaStructureContent.load(contentRoot)
        val imports = ArenaImportContent.load(contentRoot)
        val generators = GeneratorContents()
        assertThat(generators.bootstrap(contentRoot.resolve("generators")).failures).isEmpty()
        for (generator in generators.all()) {
            val stage = stages[generator.matchStageId]
            assertThat(stage).describedAs("generator '${generator.id}' targets an unknown stage '${generator.matchStageId}'").isNotNull
            val plan = ArenaIslandGenerator.generate(stage!!, parts, imports)
            // The anchor and every candidate spot / cache: solid ground directly below, free space at the spot's own height.
            for (pos in listOf(generator.position) + generator.spots) {
                val x = floor(pos.x).toInt()
                val y = floor(pos.y).toInt()
                val z = floor(pos.z).toInt()
                assertThat(plan.isSolid(x, y - 1, z)).describedAs("generator '${generator.id}' spot ($x, $y, $z) has no ground below it in '${stage.id.value}'").isTrue
                assertThat(plan.isSolid(x, y, z)).describedAs("generator '${generator.id}' spot ($x, $y, $z) is inside a block in '${stage.id.value}'").isFalse
            }
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
