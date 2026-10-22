package com.astarworks.astera.architecture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Founder decision 2026-10-23: unit tests are forbidden; only integration tests are allowed
 * (docs/contributing/testing-policy.md). Under `plugin/<module>/src/test/` a test class must be
 * `*IT` (real DB), `Shipped*Test` (real shipped `content/`), or listed in
 * `konsist-allowlist/plugin-tests.txt`. Non-test helper files are only allowed under `harness/`.
 * Integration tests belong in `tools/game-sim`, `tools/e2e`.
 */
class TestPolicyTest {

    private val root: Path = generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }

    private val testAnnotation = Regex("@(Test|ParameterizedTest|TestFactory|RepeatedTest|Nested)\\b")

    @Test
    fun `plugin test sources hold only integration tests`() {
        val allowlist = javaClass.getResourceAsStream("/konsist-allowlist/plugin-tests.txt")!!
            .bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

        val pluginTestFile = Regex("^plugin/[^/]+/src/test/")
        val violations = Files.walk(root.resolve("plugin")).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .map { root.relativize(it).toString().replace('\\', '/') }
                .filter { pluginTestFile.containsMatchIn(it) }
                .toList()
        }.filter { rel ->
            val name = rel.substringAfterLast('/')
            val isTest = testAnnotation.containsMatchIn(Files.readString(root.resolve(rel)))
            val ok = rel in allowlist ||
                (isTest && (name.endsWith("IT.kt") || (name.startsWith("Shipped") && name.endsWith("Test.kt")))) ||
                (!isTest && "/harness/" in rel)
            !ok
        }.sorted()

        assertThat(violations)
            .`as`("Unit tests are forbidden under plugin/*/src/test. Use tools/game-sim, tools/e2e or a *IT (see docs/contributing/testing-policy.md)")
            .isEmpty()
    }
}
