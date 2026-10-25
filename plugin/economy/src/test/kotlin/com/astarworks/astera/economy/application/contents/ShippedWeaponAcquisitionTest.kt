package com.astarworks.astera.economy.application.contents

import com.astarworks.astera.core.application.contents.ItemContents
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.economy.application.service.InMemoryShopRegistry
import com.astarworks.astera.economy.application.service.InMemoryShopStockRepository
import com.astarworks.astera.economy.application.service.ShopLoaderService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * ECO-01 / ECO-03 / ECO-05 / ECO-06 against the real `content/`: every playable weapon has an
 * acquisition route, shop prices rise with rarity, starter and demo weapons are not sold, and no
 * live quest or drop table points at an item that does not exist.
 */
class ShippedWeaponAcquisitionTest {

    private val content: Path = locateContentRoot()

    /** Dev / demo items: never on a player-facing route. */
    private val demoItems = setOf("rtm-blaster", "skill-tester")

    private val items: ItemContents = ItemContents().also {
        assertThat(it.bootstrap(content.resolve("items")).failures).isEmpty()
    }

    private val weapons: List<ItemSpec> = items.all().filter { it.equipSlot == EquipSlot.MAINHAND }

    private val shopOffers = run {
        val registry = InMemoryShopRegistry()
        val report = ShopLoaderService(registry, InMemoryShopStockRepository()).loadFrom(content.resolve("shops"))
        assertThat(report.failures).isEmpty()
        registry.all().flatMap { it.offers }
    }

    private fun starterItems(): Set<String> =
        topLevelList(content.resolve("balance/starter-grant.yaml").readText(), "starter_items")

    /** Item ids listed under `items: [a, b]` of level-rewards.yaml (entitlement grants). */
    private fun levelRewardItems(): Set<String> =
        Regex("""items:\s*\[([^\]]*)]""").findAll(content.resolve("progression/level-rewards.yaml").readText())
            .flatMap { it.groupValues[1].split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    @Test
    fun `every playable weapon has an acquisition route`() {
        val routes = shopOffers.map { it.itemId.value }.toSet() + starterItems() + levelRewardItems()
        val unreachable = weapons.map { it.id.value }.filter { it !in demoItems && it !in routes }
        assertThat(unreachable).`as`("weapons with no shop/starter/level-reward route").isEmpty()
    }

    @Test
    fun `demo weapons are on no player-facing route`() {
        val routes = shopOffers.map { it.itemId.value }.toSet() + starterItems() + levelRewardItems()
        assertThat(routes).doesNotContainAnyElementsOf(demoItems)
    }

    @Test
    fun `starter weapons are not sold in the shop`() {
        assertThat(shopOffers.map { it.itemId.value }).doesNotContainAnyElementsOf(starterItems())
    }

    @Test
    fun `shop prices are ordered by rarity - every tier strictly above the one below`() {
        val byRarity = shopOffers.groupBy { checkNotNull(items.find(it.itemId)).rarity }.toSortedMap()
        byRarity.values.zipWithNext().forEach { (lower, higher) ->
            val lowMax = lower.maxOf { it.price.amount }
            val highMin = higher.minOf { it.price.amount }
            assertThat(highMin)
                .`as`("cheapest ${higher.first().itemId.value}-tier offer must cost more than the dearest of the tier below")
                .isGreaterThan(lowMax)
        }
        assertThat(shopOffers.map { it.price.currency }.toSet()).containsOnly(com.astarworks.astera.core.domain.economy.Currency.GOLD)
    }

    @Test
    fun `no live quest or drop table references an item that does not exist`() {
        val known = items.all().map { it.id.value }.toSet()
        val refs = listOf("quests", "pve/drop-tables").flatMap { dir ->
            Files.list(content.resolve(dir)).use { s ->
                s.filter { it.isRegularFile() && it.extension == "yaml" }.toList()
            }.flatMap { file ->
                Regex("""(?:item_id|item):\s*([a-z0-9-]+)""").findAll(file.readText()).map { file.fileName.toString() to it.groupValues[1] }.toList()
            }
        }
        assertThat(refs.filter { it.second !in known }).`as`("dangling item references (freeze them under _frozen/)").isEmpty()
    }

    @Test
    fun `frozen survival content is not loaded`() {
        for (dir in listOf("gathering", "recipes")) {
            val top = Files.list(content.resolve(dir)).use { s -> s.filter { it.isRegularFile() && it.extension == "yaml" }.count() }
            assertThat(top).`as`("content/$dir must have no live yaml (frozen under _frozen/)").isZero
        }
    }

    private fun topLevelList(text: String, key: String): Set<String> {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.startsWith("$key:") }
        require(start >= 0) { "$key not found" }
        return lines.drop(start + 1).takeWhile { it.trimStart().startsWith("- ") || it.isBlank() || it.trimStart().startsWith("#") }
            .filter { it.trimStart().startsWith("- ") }.map { it.trimStart().removePrefix("- ").trim() }.toSet()
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
