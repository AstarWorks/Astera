package com.astarworks.astera.match.application.contents

import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.match.domain.matchmaking.LaneKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Guards that every shipped `content/` file owned by Match decodes through
 * its production loader against the repo's real `content/` directory. Failures
 * name the offending file + message.
 */
class ShippedMatchContentDecodesTest {

    private val contentRoot: Path = locateContentRoot()

    @TestFactory
    fun `every shipped match content file decodes`(): List<DynamicTest> {
        val cases: List<Pair<String, (Path) -> ContentLoader.LoadReport>> = listOf(
            "stages" to { d -> StageContents().bootstrap(d) },
            "anomaly" to { d -> AnomalyContents().bootstrap(d) },
            "generators" to { d -> GeneratorContents().bootstrap(d) },
            "game-modes" to { d -> GameModeContents().bootstrap(d) },
        )
        return cases.map { (category, load) ->
            DynamicTest.dynamicTest(category) {
                val dir = contentRoot.resolve(category)
                val report = load(dir)
                assertThat(report.failures)
                    .withFailMessage {
                        "content/$category failed to load: " +
                            report.failures.joinToString("; ") { "${it.file.fileName} -> ${it.message}" }
                    }
                    .isEmpty()
                assertThat(report.failed).isZero
                assertThat(report.loaded).isEqualTo(shippedFileCount(dir))
                assertThat(report.loaded).`as`("content/$category must ship at least one file").isGreaterThan(0)
            }
        }
    }

    /**
     * MODE-05 / MODE-06: every game mode is reachable (has a queue lane), every lane names a game mode and a stage
     * that exists with spawns for each team and star generators (PvE aside), and no two lanes share a stage, because
     * a stage hosts one match at a time and stages are not instanced yet (so a shared stage serialises the lanes).
     */
    @org.junit.jupiter.api.Test
    fun `every game mode has a queue lane on its own valid stage`() {
        val modes = GameModeContents().also { assertThat(it.bootstrap(contentRoot.resolve("game-modes")).failures).isEmpty() }
            .all().map { it.id.value }.toSet()
        val queue = QueueSettingsContents().also { assertThat(it.bootstrap(contentRoot.resolve("matchmaking")).failures).isEmpty() }
            .current()
        val stages = StageContents().also { assertThat(it.bootstrap(contentRoot.resolve("stages")).failures).isEmpty() }
            .all().associateBy { it.id.value }
        val generators = GeneratorContents().also { assertThat(it.bootstrap(contentRoot.resolve("generators")).failures).isEmpty() }
            .all().groupBy { it.matchStageId }

        assertThat(queue.lanes.keys).describedAs("every game mode needs a queue lane (and every lane a game mode)").isEqualTo(modes)
        for (lane in queue.lanes.values) {
            val stage = stages[lane.stage.value]
            assertThat(stage).describedAs("lane '${lane.key}' names unknown stage '${lane.stage.value}'").isNotNull
            val teamSpawns = stage!!.spawnPoints.mapNotNull { it.team }.toSet()
            assertThat(teamSpawns.size).describedAs("lane '${lane.key}': team spawns on '${lane.stage.value}'").isGreaterThanOrEqualTo(if (lane.kind == LaneKind.PVE) 1 else lane.teams)
            if (lane.kind != LaneKind.PVE) {
                assertThat(generators[lane.stage.value]).describedAs("lane '${lane.key}': star generators on '${lane.stage.value}'").isNotEmpty
            }
        }
        val byStage = queue.lanes.values.groupBy { it.stage.value }
        assertThat(byStage.filterValues { it.size > 1 })
            .describedAs("lanes sharing a stage cannot run concurrently (one match per stage)")
            .isEmpty()
    }

    private fun shippedFileCount(dir: Path): Int {
        val ext = setOf("yaml", "yml", "toml", "json")
        return Files.list(dir).use { s ->
            s.filter { it.isRegularFile() && it.extension.lowercase() in ext }.count().toInt()
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
