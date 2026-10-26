package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.application.config.parser.JsonContentParser
import com.astarworks.astera.core.application.config.parser.TomlContentParser
import com.astarworks.astera.core.application.config.parser.YamlContentParser
import com.astarworks.astera.core.application.port.outbound.IContentParser
import com.astarworks.astera.core.application.port.outbound.IShopStockRepository
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOfferId
import com.astarworks.astera.core.domain.shop.ShopStock
import com.astarworks.astera.economy.application.config.ShopYamlConfig
import java.nio.file.Path

/**
 * Reads shop definition files under `content/shops/` and registers parsed specs
 * in the supplied [InMemoryShopRegistry] (ADR-0027 / Wave 4.5 R3.5).
 *
 * Thin wrapper over [ContentLoader] specialized to [ShopYamlConfig]: it supplies
 * the default multi-format parser list (YAML / TOML / JSON per ADR-0016) and
 * converts each loaded DTO to a domain `ShopSpec` via [ShopYamlConfig.toSpec]
 * before handing it to the registry.
 *
 * ## Wave 5g (Core hardening) — stock seeding
 *
 * After each [com.astarworks.astera.core.domain.shop.ShopSpec] is registered,
 * the loader walks its offers and seeds a `shop_stock` row for every
 * [ShopStock.Limited] offer via [IShopStockRepository.seed]. `seed` is
 * idempotent — re-running the loader on plugin restart does **not** reset
 * partially-bought offers back to the YAML initial value. Operators wanting
 * to reset stock must clear the table (`DELETE FROM shop_stock WHERE …`).
 * [ShopStock.Unlimited] offers are intentionally skipped; the stock view
 * treats "no row" as "no limit" (see [IShopStockRepository.currentStock]).
 *
 * Failure mode: a single bad file logs an error and is skipped; the rest still
 * load. Phase 1 has no hot-reload — call [loadFrom] once at startup. Mirrors
 * `GuiLoaderService` / `WeaponLoaderService` 1:1 so the operator experience is
 * uniform across content kinds.
 */
public class ShopLoaderService(
    private val registry: InMemoryShopRegistry,
    private val stockRepository: IShopStockRepository,
    parsers: List<IContentParser> = DEFAULT_PARSERS,
) {
    private val loader = ContentLoader(
        parsers = parsers,
        deserializer = ShopYamlConfig.serializer(),
    )

    /** A shop offer whose limited stock row still has to be written by [seed]. */
    public data class PendingSeed(val shop: ShopId, val offer: ShopOfferId, val remaining: Int)

    /** Result of [registerFrom]: the load report plus the stock rows [seed] must write. */
    public data class Registered(val report: LoadReport, val seeds: List<PendingSeed>)

    /**
     * Registers every supported content file in [dir] synchronously (no I/O beyond reading the files). Missing dir =
     * warn + no-op. Limited-stock rows are returned for [seed]; they are not written here.
     */
    public fun registerFrom(dir: Path): Registered {
        val seeds = mutableListOf<PendingSeed>()
        val report = loader.loadFrom(dir) { cfg ->
            val spec = cfg.toSpec()
            registry.register(spec)
            spec.offers.forEach { offer ->
                val stock = offer.stock
                if (stock is ShopStock.Limited) seeds += PendingSeed(spec.id, offer.id, stock.remaining)
            }
        }
        return Registered(LoadReport(loaded = report.loaded, failed = report.failed, failures = report.failures), seeds)
    }

    /** Writes the idempotent stock rows collected by [registerFrom]. */
    public suspend fun seed(seeds: List<PendingSeed>) {
        seeds.forEach { stockRepository.seed(it.shop, it.offer, it.remaining) }
    }

    /** [registerFrom] followed by [seed]. */
    public suspend fun loadFrom(dir: Path): LoadReport {
        val registered = registerFrom(dir)
        seed(registered.seeds)
        return registered.report
    }

    public data class LoadReport(
        val loaded: Int,
        val failed: Int,
        val failures: List<ContentLoader.LoadFailure> = emptyList(),
    )

    public companion object {
        /**
         * Default parser list: YAML + TOML + JSON. Matches the ADR-0016
         * Phase 2 mid wire-format support matrix.
         */
        public val DEFAULT_PARSERS: List<IContentParser> = listOf(
            YamlContentParser(),
            TomlContentParser(),
            JsonContentParser(),
        )
    }
}
