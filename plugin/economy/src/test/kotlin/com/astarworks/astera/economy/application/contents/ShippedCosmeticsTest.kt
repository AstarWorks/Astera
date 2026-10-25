package com.astarworks.astera.economy.application.contents

import com.astarworks.astera.core.application.contents.CosmeticContents
import com.astarworks.astera.core.domain.cosmetic.CosmeticKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** ECO-02 / ECO-04: the shipped `content/cosmetics` decodes and every reward points at a real cosmetic. */
class ShippedCosmeticsTest {

    private val content: Path = locateContentRoot()
    private val catalog = CosmeticContents().also {
        assertThat(it.bootstrap(content.resolve("cosmetics")).failures).isEmpty()
    }

    private fun yamlFiles(dir: String): List<Path> =
        Files.list(content.resolve(dir)).use { s -> s.filter { it.isRegularFile() && it.extension == "yaml" }.sorted().toList() }

    @Test
    fun `catalog has all three kinds, sold and grant-only entries, and every name is translated`() {
        val all = catalog.all()
        assertThat(all.map { it.kind }.toSet()).containsExactlyInAnyOrder(*CosmeticKind.entries.toTypedArray())
        assertThat(all.filter { it.priceGold != null }).hasSizeGreaterThanOrEqualTo(8)
        assertThat(all.filter { it.priceGold == null }).isNotEmpty
        assertThat(all.size).isLessThanOrEqualTo(45) // one menu page
        for (lang in listOf("en", "ja")) {
            val text = content.resolve("languages/$lang.yaml").readText()
            for (c in all) {
                val leaf = c.nameKey.value.substringAfterLast('.')
                assertThat(text).`as`("$lang name of ${c.nameKey.value}").contains("      $leaf: \"")
            }
        }
    }

    @Test
    fun `every sink is priced above the cheapest weapon-free impulse buy and kill effects cost more than titles`() {
        val sold = catalog.all().filter { it.priceGold != null }
        assertThat(sold.minOf { it.priceGold!! }).isGreaterThanOrEqualTo(100L)
        val maxTitle = sold.filter { it.kind == CosmeticKind.TITLE }.maxOf { it.priceGold!! }
        val minEffect = sold.filter { it.kind == CosmeticKind.KILL_EFFECT }.minOf { it.priceGold!! }
        assertThat(minEffect).isLessThanOrEqualTo(maxTitle + 100L)
        assertThat(sold.filter { it.kind == CosmeticKind.KILL_EFFECT }.maxOf { it.priceGold!! }).isGreaterThan(maxTitle)
    }

    @Test
    fun `level and achievement rewards only grant cosmetics that exist`() {
        val levels = content.resolve("progression/level-rewards.yaml").readText()
        val granted = mutableListOf<Pair<String, String>>() // unlock prefix to id
        Regex("""titles:\s*\[([^\]]*)]""").findAll(levels).forEach { m ->
            m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { granted += "title" to it }
        }
        Regex("""cosmetics:\s*\[([^\]]*)]""").findAll(levels).forEach { m ->
            m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { granted += "cosmetic" to it }
        }
        yamlFiles("achievements").forEach { f ->
            Regex("""^reward_title:\s*(\S+)""", RegexOption.MULTILINE).find(f.readText())?.let { granted += "title" to it.groupValues[1] }
        }
        assertThat(granted.filter { it.first == "title" }).hasSizeGreaterThanOrEqualTo(6)
        for ((prefix, id) in granted) {
            val c = catalog.find(id)
            assertThat(c).`as`("$prefix:$id exists in content/cosmetics").isNotNull
            assertThat(c!!.unlockId).isEqualTo("$prefix:$id")
            assertThat(c.priceGold).`as`("$id is granted, so not also sold").isNull()
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
