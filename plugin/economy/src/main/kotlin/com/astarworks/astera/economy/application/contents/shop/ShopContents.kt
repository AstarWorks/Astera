package com.astarworks.astera.economy.application.contents.shop

import com.astarworks.astera.core.application.contents.EmptyReloadGuard
import com.astarworks.astera.core.application.contents.ReloadableContents
import com.astarworks.astera.core.application.port.outbound.IShopStockRepository
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.shop.ShopSpec
import com.astarworks.astera.core.domain.shop.ShopStock
import com.astarworks.astera.economy.application.config.ShopYamlConfig
import com.astarworks.astera.economy.application.service.InMemoryShopRegistry
import com.astarworks.astera.economy.application.service.MatchShopLoaderService
import com.astarworks.astera.economy.application.service.ShopLoaderService
import java.nio.file.Path

/**
 * `content/shops/` as a reload category (`shops`). A reload diffs the new specs against the live
 * registry and applies the stock changes the diff implies; a bad file fails the whole category and
 * leaves registry, stock and match shop untouched.
 *
 *  - new limited offer: seeded; limited offer whose limit changed: remaining stock is clamped to the
 *    new limit (never raised); offer removed or no longer limited: its stock row is dropped; every
 *    other offer keeps its stock.
 *  - the in-match ore catalog (`match-shop.yaml`) is match-scoped: it applies at the next match start
 *    while a match is running (see [MatchShopLoaderService.promotePending]).
 */
public class ShopContents(
    private val registry: InMemoryShopRegistry,
    private val stock: IShopStockRepository,
    private val matchShop: MatchShopLoaderService,
    private val matchRunning: () -> Boolean,
    /** Runs after the registry was swapped (refresh open shop GUIs); the host decides the thread. */
    private val onApplied: () -> Unit = {},
) : ReloadableContents {

    override val categoryName: String = "shops"

    private val loader = ContentLoader(
        parsers = ShopLoaderService.DEFAULT_PARSERS,
        deserializer = ShopYamlConfig.serializer(),
    )

    override fun reload(dir: Path): ContentLoader.LoadReport {
        val specs = mutableListOf<ShopSpec>()
        val report = loader.loadFrom(dir, accept = { it.fileName.toString() != MatchShopLoaderService.FILE_NAME }) { cfg ->
            specs += cfg.toSpec()
        }
        if (report.failed > 0) return report
        EmptyReloadGuard.check(categoryName, dir, specs.size, registry.all().size)?.let { return it }
        val (matchReport, matchCatalog) = matchShop.parse(dir)
        if (matchReport.failed > 0) return matchReport

        val old = registry.all().associateBy { it.id }
        val notes = mutableListOf<String>()
        val errors = mutableListOf<ContentLoader.LoadFailure>()
        fun check(what: String, result: Result<Unit, *>) {
            if (result is Result.Failure) errors += ContentLoader.LoadFailure(dir, "$what: ${result.error}")
        }

        for (spec in specs) {
            val before = old[spec.id]?.offers?.associateBy { it.id } ?: emptyMap()
            var added = 0
            var removed = 0
            var clamped = 0
            var limitChanged = 0
            for (offer in spec.offers) {
                val prev = before[offer.id]
                val next = offer.stock
                if (prev == null) added++
                if (next is ShopStock.Limited) {
                    check("seed ${spec.id.value}/${offer.id.value}", stock.seed(spec.id, offer.id, next.remaining))
                    val prevStock = prev?.stock
                    if (prevStock is ShopStock.Limited && prevStock.remaining != next.remaining) {
                        limitChanged++
                        if (next.remaining < prevStock.remaining) clamped++
                    }
                    check("clamp ${spec.id.value}/${offer.id.value}", stock.clampTo(spec.id, offer.id, next.remaining))
                } else if (prev?.stock is ShopStock.Limited) {
                    check("remove ${spec.id.value}/${offer.id.value}", stock.remove(spec.id, offer.id))
                }
            }
            val now = spec.offers.map { it.id }.toSet()
            for (gone in before.values.filter { it.id !in now }) {
                removed++
                if (gone.stock is ShopStock.Limited) check("remove ${spec.id.value}/${gone.id.value}", stock.remove(spec.id, gone.id))
            }
            notes += "shop ${spec.id.value}: +$added offers, -$removed offers, $limitChanged limits changed ($clamped stock clamped), rest kept"
        }
        for (gone in old.values.filter { o -> specs.none { it.id == o.id } }) {
            gone.offers.filter { it.stock is ShopStock.Limited }.forEach { check("remove ${gone.id.value}/${it.id.value}", stock.remove(gone.id, it.id)) }
            notes += "shop ${gone.id.value}: removed (${gone.offers.size} offers dropped)"
        }
        if (errors.isNotEmpty()) return ContentLoader.LoadReport(loaded = 0, failed = errors.size, failures = errors)

        registry.replaceAll(specs)
        onApplied()
        if (matchCatalog != null) {
            if (matchRunning()) {
                matchShop.stage(matchCatalog)
                notes += "match-shop: a match is running, the new catalog applies from the next match (running matches keep the old one)"
            } else {
                matchShop.apply(matchCatalog)
                notes += "match-shop: applied (no match running)"
            }
        }
        return ContentLoader.LoadReport(loaded = specs.size, failed = 0, notes = notes)
    }
}
