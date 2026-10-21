package com.astarworks.astera.stats.application.contents

import com.astarworks.astera.core.application.service.ContentLoader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Guards that every shipped `content/` file owned by Stats decodes through
 * its production loader against the repo's real `content/` directory. Failures
 * name the offending file + message.
 */
class ShippedStatsContentDecodesTest {

    private val contentRoot: Path = locateContentRoot()

    @TestFactory
    fun `every shipped stats content file decodes`(): List<DynamicTest> {
        val cases: List<Pair<String, (Path) -> ContentLoader.LoadReport>> = listOf(
            "norma" to { d -> NormaContents().bootstrap(d) },
            "skills-progression" to { d -> SkillProgressionContents().bootstrap(d) },
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
