package com.astarworks.astera.architecture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * Every complete `astera.hud.*` message key written as a string literal in production Kotlin must
 * exist in `content/languages/en.yaml`, so a HUD / message caller can no longer ship a key that
 * renders as its raw id. (en <-> ja parity is a separate rule.)
 *
 * Scoped to the HUD namespace: other `astera.*` literals include permission nodes, command ids and
 * broadcast channels that are not message keys (widen once those are told apart).
 *
 * Literals that are a parent node of existing keys (prefixes such as `astera.hud`) or end in a
 * separator are treated as prefixes the code extends at runtime and are not checked.
 */
class I18nKeyCoverageTest {

    private val root: Path = generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }

    @Test
    fun `every astera hud message key literal in production Kotlin exists in en yaml`() {
        val defined = flattenYamlKeys(root.resolve("content/languages/en.yaml").readText())
        val literal = Regex("\"(astera\\.hud(?:\\.[a-z0-9_-]+)+)\"")

        val missing = Files.walk(root.resolve("plugin")).use { paths ->
            paths.filter { it.extension == "kt" && it.toString().contains("/src/main/") }
                .toList()
                .flatMap { file ->
                    file.readText().lineSequence()
                        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                        .flatMap { line -> literal.findAll(line).map { it.groupValues[1] } }
                        .filter { key -> key !in defined && defined.none { it.startsWith("$key.") } }
                        .map { key -> "$key (${root.relativize(file)})" }
                        .toList()
                }
        }.distinct().sorted()

        assertThat(missing).`as`("astera.hud.* keys used in code but missing from content/languages/en.yaml").isEmpty()
    }

    /** Dotted paths of every leaf scalar in a nested 2-space-indented YAML map (keys only, no anchors). */
    private fun flattenYamlKeys(yaml: String): Set<String> {
        val stack = ArrayDeque<Pair<Int, String>>()
        val keys = mutableSetOf<String>()
        for (raw in yaml.lineSequence()) {
            if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
            val indent = raw.length - raw.trimStart().length
            val m = Regex("^\\s*(?:\"([^\"]+)\"|([A-Za-z0-9_.-]+)):(.*)$").matchEntire(raw) ?: continue
            val key = m.groupValues[1].ifEmpty { m.groupValues[2] }
            while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeLast()
            val path = (stack.map { it.second } + key).joinToString(".")
            val rest = m.groupValues[3].trim()
            if (rest.isEmpty()) stack.addLast(indent to key) else keys += path
        }
        return keys
    }
}
