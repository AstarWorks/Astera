package com.astarworks.astera.core.application.contents

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * First-ten-minutes clarity on the shipped content: each guided tutorial control step names the exact
 * input in both languages, and every trigger id used by shipped item lore is one `ItemTooltip` labels.
 */
class ShippedTutorialControlsTest {

    private val root = locateRepoRoot()

    private fun guideText(lang: String, step: String): String {
        val keys = listOf("subtitle", "hint")
        val lines = Files.readAllLines(root.resolve("content/languages/$lang.yaml"))
        return keys.joinToString(" ") { k ->
            val prefix = "  tutorial.guide.$step.$k:"
            checkNotNull(lines.firstOrNull { it.startsWith(prefix) }) { "missing $prefix in $lang.yaml" }
        }
    }

    @Test
    fun `tutorial progress steps name the exact input in en and ja`() {
        val expected = mapOf(
            "menu" to (listOf("press F") to listOf("F")),
            "queue" to (listOf("right-click") to listOf("右クリック")),
            "result" to (listOf("Last result") to listOf("直近の結果")),
            "shop" to (listOf("right-click") to listOf("右クリック")),
            "equip" to (listOf("hotbar") to listOf("ホットバー")),
            "missions" to (listOf("Daily/Quests") to listOf("デイリー/クエスト")),
        )
        expected.forEach { (step, pair) ->
            val en = guideText("en", step).lowercase()
            pair.first.forEach { assertThat(en).describedAs("en step $step").contains(it.lowercase()) }
            val ja = guideText("ja", step)
            pair.second.forEach { assertThat(ja).describedAs("ja step $step").contains(it) }
        }
    }

    @Test
    fun `shipped item lore only uses trigger ids the tooltip labels`() {
        val known = setOf("left_click", "right_click", "sneak_right", "sneak_left", "drop")
        val raw = Regex("^\\s*-\\s*['\"]?([a-z]+(?:_[a-z]+)*):")
        val used = mutableSetOf<String>()
        Files.list(root.resolve("content/items")).use { files ->
            files.filter { it.toString().endsWith(".yaml") }.forEach { f ->
                var inLore = false
                Files.readAllLines(f).forEach { line ->
                    if (line.startsWith("lore:")) inLore = true
                    else if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("#")) inLore = false
                    if (inLore) raw.find(line)?.let { used += it.groupValues[1] }
                }
            }
        }
        assertThat(used).describedAs("raw trigger ids in item lore").isNotEmpty()
        assertThat(known).containsAll(used)
    }

    private fun locateRepoRoot(): Path {
        var cur: Path? = Path.of("").toAbsolutePath()
        while (cur != null) {
            if (Files.isRegularFile(cur.resolve("settings.gradle.kts")) && Files.isDirectory(cur.resolve("content"))) return cur
            cur = cur.parent
        }
        error("repo root not found")
    }
}
