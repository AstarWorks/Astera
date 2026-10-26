package com.astarworks.astera.architecture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * Every complete `astera.*` message key written as a string literal in production Kotlin
 * (every plugin module's `src/main` tree) must exist in `content/languages/en.yaml`, so a message / HUD / GUI caller
 * can no longer ship a key that renders as its raw id. (en <-> ja parity is a separate rule.)
 *
 * Not checked (not message keys):
 * - Permission nodes: any literal on a line that builds a `Permission(...)`, names a `PERM_*` /
 *   `*_PERMISSION` constant, or sits in a `forEveryone(` / `requirePermission(` call or an `allow:`
 *   config line.
 * - Proxy channel ids: `ProxyChannel("...")`.
 * - Attribute modifier ids and KDoc-only prefixes: [NON_MESSAGE_LITERALS].
 * - Literals in KDoc / line comments (lines starting with `*` or `//`, and block-doc openers).
 * - Dynamic keys (`${...}` / `$var`) and wildcard / placeholder text (`astera.*`, `astera.<area>.*`):
 *   the literal regex only matches fully static dotted lowercase keys.
 *
 * Literals that are a parent node of existing keys (prefixes such as `astera.hud`) are treated as
 * prefixes the code extends at runtime and are not checked.
 */
class I18nKeyCoverageTest {

    private val root: Path = generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }

    @Test
    fun `every astera message key literal in production Kotlin exists in en yaml`() {
        val defined = flattenYamlKeys(root.resolve("content/languages/en.yaml").readText())
        val literal = Regex("\"(astera(?:\\.[a-z0-9_-]+)+)\"")
        val nonMessageContext = Regex(
            "Permission\\(|PERM_|_PERMISSION|ProxyChannel\\(|forEveryone\\(|requirePermission\\(|allow:|perm =",
        )

        val missing = java.util.stream.Stream.of("plugin", "kernel", "core").flatMap { Files.walk(root.resolve(it)) }.use { paths ->
            paths.filter { it.extension == "kt" && it.toString().contains("/src/main/") }
                .toList()
                .flatMap { file ->
                    file.readText().lineSequence()
                        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                        .filterNot { it.contains("/**") || nonMessageContext.containsMatchIn(it) }
                        .flatMap { line -> literal.findAll(line).map { it.groupValues[1] } }
                        .filter { key -> key !in NON_MESSAGE_LITERALS }
                        .filter { key -> key !in defined && defined.none { it.startsWith("$key.") } }
                        .map { key -> "$key (${root.relativize(file)})" }
                        .toList()
                }
        }.distinct().sorted()

        assertThat(missing).`as`("astera.* message keys used in code but missing from content/languages/en.yaml").isEmpty()
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

    private companion object {
        /** Static `astera.*` literals that are identifiers or doc prefixes, not message keys (see class KDoc). */
        val NON_MESSAGE_LITERALS = setOf(
            "astera.movement_speed", // attribute modifier id
            "astera.knockback_resistance", // attribute modifier id
            "astera.weapon", // prefix named in a KDoc example
            "astera.broadcast.events", // Redis pub/sub channel id (LettuceRedisBroadcaster)
            "astera.menu.open", // permission node of the /menu GUI shortcut (CoreWiringGameplay)
            "astera.settings.open", // permission node of the /settings GUI shortcut
            "astera.social.open", // permission node of the /social GUI shortcut
        )
    }
}
