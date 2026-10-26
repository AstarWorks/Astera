package com.astarworks.astera.economy.application.contents

import com.astarworks.astera.core.application.contents.ItemContents
import com.astarworks.astera.core.application.contents.LoginBonusContents
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/** ECO-09 / ECO-04 reward-table invariants against the real `content/` (no inversions, no placeholders). */
class ShippedRewardTablesTest {

    private val content: Path = locateContentRoot()

    private fun yamlFiles(dir: String): List<Path> =
        Files.list(content.resolve(dir)).use { s -> s.filter { it.isRegularFile() && it.extension == "yaml" }.sorted().toList() }

    private fun field(text: String, key: String): String? =
        Regex("""^\s*$key:\s*(\S+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)

    @Test
    fun `login bonus grows every day and day 7 is the peak`() {
        val contents = LoginBonusContents()
        assertThat(contents.bootstrap(content.resolve("login-bonus")).failures).isEmpty()
        val days = contents.all().sortedBy { it.day }
        assertThat(days.map { it.day }).containsExactly(1, 2, 3, 4, 5, 6, 7)
        val amounts = days.map { it.reward.amount }
        assertThat(amounts).isSorted
        assertThat(amounts.toSet()).`as`("strictly increasing").hasSameSizeAs(amounts)
        assertThat(amounts.last()).isGreaterThanOrEqualTo(amounts[amounts.size - 2] * 3 / 2)
    }

    @Test
    fun `weekly missions pay more than any daily and norma ids match their file names`() {
        val missions = yamlFiles("norma").map { f ->
            val text = f.readText()
            assertThat(field(text, "id")).`as`("id of ${f.name}").isEqualTo(f.name.removeSuffix(".yaml"))
            Triple(field(text, "period")!!, field(text, "currency_amount")!!.toLong(), field(text, "xp")!!.toLong())
        }
        val daily = missions.filter { it.first == "daily" }
        val weekly = missions.filter { it.first == "weekly" }
        assertThat(daily).isNotEmpty
        assertThat(weekly).isNotEmpty
        assertThat(weekly.minOf { it.second }).isGreaterThan(daily.maxOf { it.second })
        assertThat(weekly.minOf { it.third }).isGreaterThan(daily.maxOf { it.third })
    }

    @Test
    fun `level rewards use round amounts, never decrease and extend past level 10`() {
        val golds = Regex("""^\s{2}(\d+):\s*\n\s+gold:\s*(\d+)""", RegexOption.MULTILINE)
            .findAll(content.resolve("progression/level-rewards.yaml").readText())
            .map { it.groupValues[1].toInt() to it.groupValues[2].toLong() }.toList().sortedBy { it.first }
        assertThat(golds.map { it.first }.max()).isGreaterThanOrEqualTo(30)
        assertThat(golds.filter { it.second % 25 != 0L }).`as`("amounts that are not multiples of 25").isEmpty()
        assertThat(golds.map { it.second }).isSorted
    }

    @Test
    fun `every achievement has a gold reward and a distinct existing icon`() {
        val items = ItemContents().also { it.bootstrap(content.resolve("items")) }
        val files = yamlFiles("achievements").map { it.readText() }
        val icons = files.map { field(it, "icon_item_id")!! }
        assertThat(icons).doesNotHaveDuplicates()
        icons.forEach { assertThat(items.find(com.astarworks.astera.core.domain.item.ItemId(it))).`as`("icon item $it").isNotNull }
        assertThat(files.map { field(it, "reward_gold")?.toLong() ?: 0L }).allMatch { it > 0L }
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
