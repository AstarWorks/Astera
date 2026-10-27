package com.astarworks.astera.combat.application.contents

import com.astarworks.astera.combat.application.config.ElementMatrixYamlConfig
import com.astarworks.astera.combat.application.config.StatusEffectInteractionLoader
import com.astarworks.astera.core.application.config.parser.YamlContentParser
import com.astarworks.astera.core.application.service.ContentLoader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Guards that every shipped `content/` file owned by Combat (skills, status,
 * armor, combat-depth/combos, balance element matrix + status interactions)
 * decodes through its production loader. Failures name file + message.
 */
class ShippedCombatContentDecodesTest {

    private val contentRoot: Path = locateContentRoot()

    @TestFactory
    fun `every shipped combat content file decodes`(): List<DynamicTest> {
        val cases: List<Pair<String, (Path) -> ContentLoader.LoadReport>> = listOf(
            "status" to { d -> StatusContents().bootstrap(d) },
            "armor" to { d -> ArmorContents().bootstrap(d) },
            "combat-depth/combos" to { d -> ComboRouteContents().bootstrap(d) },
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

    @Test
    fun `balance element-matrix decodes`() {
        val text = Files.readString(contentRoot.resolve("balance/element-matrix.yaml"))
        val cfg = YamlContentParser().parse(text, ElementMatrixYamlConfig.serializer())
        assertThat(cfg.toTable()).isNotEmpty
    }

    @Test
    fun `balance status-effect-interactions decodes`() {
        val cfg = StatusEffectInteractionLoader.load(contentRoot.resolve("balance/status-effect-interactions.yaml"))
        assertThat(cfg.rules).isNotNull
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
