package com.astarworks.astera.architecture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the Paper plugin enable order (ADR-0022 sibling matrix, ADR-0174).
 *
 * History: Items once softdepended on Combat/Match while Combat softdepended on
 * Items. Paper broke the cycle by enabling Combat first, so `CastSkillUseCase`
 * was never wired in production while every in-process test stayed green.
 *
 * This test parses every module's `src/main/resources/plugin.yml` under `plugin/`
 * and asserts (1) the declared depend + softdepend sets equal the matrix in
 * `.claude/rules/module-layout.md`, and (2) the load graph (dependencies plus
 * `loadbefore`) is acyclic.
 */
class PluginLoadOrderTest {

    private data class Decl(
        val depend: Set<String>,
        val softdepend: Set<String>,
        val loadbefore: Set<String>,
    )

    /** Expected `depend + softdepend` per plugin (module-layout.md sibling matrix). */
    private val expectedDependencies: Map<String, Set<String>> = mapOf(
        "Astera-Core" to emptySet(),
        "Astera-Items" to setOf("Astera-Core"),
        "Astera-Combat" to setOf("Astera-Core", "Astera-Items"),
        "Astera-Match" to setOf("Astera-Core", "Astera-Combat"),
        "Astera-Stats" to setOf("Astera-Core", "Astera-Items", "Astera-Combat", "Astera-Match"),
        "Astera-Economy" to setOf("Astera-Core", "Astera-Stats", "Astera-Match"),
        "Astera-Persistence" to setOf("Astera-Core"),
    )

    private val featurePlugins = setOf(
        "Astera-Items", "Astera-Combat", "Astera-Match", "Astera-Stats", "Astera-Economy",
    )

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("settings.gradle.kts not found above ${File("").absolutePath}")
    }

    private fun parseList(text: String, key: String): Set<String> {
        val line = text.lines().firstOrNull { it.startsWith("$key:") } ?: return emptySet()
        return line.substringAfter(':').trim().removePrefix("[").removeSuffix("]")
            .split(',').map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }.toSet()
    }

    private fun loadDecls(): Map<String, Decl> {
        val pluginDir = File(repoRoot(), "plugin")
        val decls = pluginDir.listFiles { f -> f.isDirectory }.orEmpty()
            .map { File(it, "src/main/resources/plugin.yml") }
            .filter { it.isFile }
            .associate { f ->
                val text = f.readText()
                val name = text.lines().first { it.startsWith("name:") }.substringAfter(':').trim()
                name to Decl(parseList(text, "depend"), parseList(text, "softdepend"), parseList(text, "loadbefore"))
            }
        assertThat(decls.keys).`as`("every Astera plugin.yml must be found")
            .containsExactlyInAnyOrderElementsOf(expectedDependencies.keys)
        return decls
    }

    @Test
    fun `declared depend and softdepend match the ADR-0022 sibling matrix`() {
        val decls = loadDecls()
        for ((name, expected) in expectedDependencies) {
            val decl = decls.getValue(name)
            assertThat(decl.depend + decl.softdepend)
                .`as`("$name depend + softdepend")
                .isEqualTo(expected)
        }
    }

    @Test
    fun `persistence loads before every feature plugin and no other plugin uses loadbefore`() {
        val decls = loadDecls()
        assertThat(decls.getValue("Astera-Persistence").loadbefore).isEqualTo(featurePlugins)
        for ((name, decl) in decls) {
            if (name == "Astera-Persistence") continue
            assertThat(decl.loadbefore).`as`("$name loadbefore").isEmpty()
        }
    }

    @Test
    fun `plugin load graph is acyclic`() {
        val decls = loadDecls()
        // before[x] = plugins that must be enabled before x
        val before = decls.keys.associateWith { mutableSetOf<String>() }
        for ((name, decl) in decls) {
            (decl.depend + decl.softdepend).filter { it in decls }.forEach { before.getValue(name) += it }
            decl.loadbefore.filter { it in decls }.forEach { before.getValue(it) += name }
        }
        val done = mutableSetOf<String>()
        val stack = mutableListOf<String>()
        fun visit(n: String) {
            if (n in done) return
            check(n !in stack) { "plugin load cycle: ${(stack.dropWhile { it != n } + n).joinToString(" -> ")}" }
            stack += n
            before.getValue(n).forEach(::visit)
            stack.removeAt(stack.lastIndex)
            done += n
        }
        decls.keys.forEach(::visit)
    }
}
