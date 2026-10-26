package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.i18n.LanguageLoader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * Every message key that command code can reply with must exist in both languages (ADR-0184):
 * a missing key renders as the raw key in chat, which is exactly the "command does nothing"
 * experience this tree replaced. Scans the command sources of every plugin for
 * `MessageKey("...")` literals.
 */
class CommandMessageKeysTest {

    private val repoRoot: Path = generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }

    private val literal = Regex("""MessageKey\("([a-z0-9][a-z0-9._-]*)"\)""")

    private fun commandSources(): List<Path> =
        java.util.stream.Stream.of("plugin", "kernel", "core").flatMap { Files.walk(repoRoot.resolve(it)) }.use { stream ->
            stream
                .filter { it.extension == "kt" }
                .filter { "/src/main/" in it.toString() }
                .filter { p ->
                    val s = p.toString()
                    "/command/" in s || s.endsWith("MatchCommandSpecs.kt")
                }
                .toList()
        }

    @Test
    fun `every literal message key in command code exists in en and ja`() {
        val languages = LanguageLoader.loadFrom(repoRoot.resolve("content/languages"))
        val problems = mutableListOf<String>()
        for (file in commandSources()) {
            for (m in literal.findAll(file.readText())) {
                val key = m.groupValues[1]
                if (key.endsWith("-") || key.endsWith(".")) continue // prefix of a dynamic key
                for (lang in listOf("en", "ja")) {
                    if (languages[lang]?.get(key) == null) problems += "${file.fileName}: $key missing in $lang"
                }
            }
        }
        assertThat(problems.distinct()).`as`("message keys used by command code").isEmpty()
    }

    @Test
    fun `dynamic moderation reply keys exist`() {
        val languages = LanguageLoader.loadFrom(repoRoot.resolve("content/languages"))
        for (verb in listOf("kick", "mute", "unmute", "ban", "tempban", "unban", "warn")) {
            assertThat(languages["en"]?.get("astera.command.reply.mod-$verb")).`as`(verb).isNotNull()
            assertThat(languages["ja"]?.get("astera.command.reply.mod-$verb")).`as`(verb).isNotNull()
        }
    }
}
