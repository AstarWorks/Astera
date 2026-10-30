package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IShopRegistry
import com.astarworks.astera.core.application.port.outbound.IShopStockRepository
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOfferId
import com.astarworks.astera.core.domain.shop.ShopStock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** ECO reload gap: `astera admin reload shops` diff-applies stock (new seeded, removed pruned, limit lowered clamps, rest kept). */
class ShopReloadScenarioTest {

    private fun shopYaml(vararg offers: Pair<String, Int>): String = buildString {
        appendLine("id: reload-shop")
        appendLine("display_key: astera.economy.shop.example-shop.title")
        appendLine("offers:")
        for ((id, limit) in offers) {
            appendLine("  - id: $id")
            appendLine("    item: dagger")
            appendLine("    price:")
            appendLine("      currency: GOLD")
            appendLine("      amount: 10")
            appendLine("    limit: unlimited")
            appendLine("    stock:")
            appendLine("      type: limited")
            appendLine("      remaining: $limit")
            appendLine("    display_key: astera.economy.shop.example-shop.offer.dagger")
        }
    }

    @Test
    fun `reload seeds new offers, clamps a lowered limit, prunes removed offers and keeps the rest`() {
        val content = ReloadScenarioSupport.scratchContent("shops")
        val file = content.resolve("shops/reload-shop.yaml")
        Files.writeString(file, shopYaml("a" to 10, "b" to 10, "c" to 10))
        ReloadScenarioSupport.withSim("shop-reload", content) { sim ->
            val shop = ShopId("reload-shop")
            val stock = sim.services.getOrThrow<IShopStockRepository>()
            val registry = sim.services.getOrThrow<IShopRegistry>()
            fun remaining(offer: String): Int? = (stock.currentStock(shop, ShopOfferId(offer)) as? ShopStock.Limited)?.remaining
            assertThat(remaining("a")).isEqualTo(10)

            // a was bought down to 4, b to 7.
            stock.decrementStock(shop, ShopOfferId("a"), 6)
            stock.decrementStock(shop, ShopOfferId("b"), 3)
            assertThat(remaining("a")).isEqualTo(4)

            // a: limit 10 -> 20 (stock kept), b: limit 10 -> 5 (clamped), c removed, d new.
            Files.writeString(file, shopYaml("a" to 20, "b" to 5, "d" to 8))
            val report = (ReloadScenarioSupport.reload(sim, "shops") as Result.Success).value
            assertThat(report.failed).isZero()
            assertThat(report.notes.joinToString("\n")).contains("shop reload-shop").contains("clamped")
            assertThat(remaining("a")).isEqualTo(4)
            assertThat(remaining("b")).isEqualTo(5)
            assertThat(remaining("c")).isNull()
            assertThat(remaining("d")).isEqualTo(8)
            assertThat(registry.resolve(shop)!!.offers.map { it.id.value }).containsExactly("a", "b", "d")
        }
    }

    @Test
    fun `a broken shop file keeps the old shops and stock and reports an error`() {
        val content = ReloadScenarioSupport.scratchContent("shops")
        val file = content.resolve("shops/reload-shop.yaml")
        Files.writeString(file, shopYaml("a" to 10))
        ReloadScenarioSupport.withSim("shop-reload-bad", content) { sim ->
            val shop = ShopId("reload-shop")
            val stock = sim.services.getOrThrow<IShopStockRepository>()
            stock.decrementStock(shop, ShopOfferId("a"), 3)
            Files.writeString(file, "id: reload-shop\noffers: [this is not valid")
            assertThat(ReloadScenarioSupport.reload(sim, "shops")).isInstanceOf(Result.Failure::class.java)
            assertThat((stock.currentStock(shop, ShopOfferId("a")) as ShopStock.Limited).remaining).isEqualTo(7)
            assertThat(sim.services.getOrThrow<IShopRegistry>().resolve(shop)!!.offers).hasSize(1)
        }
    }
}
