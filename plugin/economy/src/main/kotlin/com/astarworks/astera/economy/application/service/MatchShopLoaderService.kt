package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.economy.application.config.MatchShopYamlConfig
import com.astarworks.astera.economy.domain.matchshop.MatchShopCatalog
import java.nio.file.Path

/** Holds the in-match shop catalog; [loadFrom] reads `content/shops/match-shop.yaml` (the lobby loader skips that file). */
public class MatchShopLoaderService {
    @Volatile
    public var catalog: MatchShopCatalog = MatchShopCatalog.EMPTY
        private set

    private val loader = ContentLoader(
        parsers = ShopLoaderService.DEFAULT_PARSERS,
        deserializer = MatchShopYamlConfig.serializer(),
    )

    @Volatile
    private var pending: MatchShopCatalog? = null

    /** Parses `match-shop.yaml` without touching [catalog]; the catalog is null when the file is absent or invalid. */
    public fun parse(shopsDir: Path): Pair<ContentLoader.LoadReport, MatchShopCatalog?> {
        var parsed: MatchShopCatalog? = null
        val report = loader.loadFrom(shopsDir, accept = { it.fileName.toString() == FILE_NAME }) { cfg -> parsed = cfg.toCatalog() }
        return report to parsed
    }

    /** Makes [next] the live catalog now. */
    public fun apply(next: MatchShopCatalog) {
        catalog = next
        pending = null
    }

    /** Holds [next] until [promotePending] (the next match start). */
    public fun stage(next: MatchShopCatalog) {
        pending = next
    }

    /** Called at match start: a staged catalog becomes live. */
    public fun promotePending() {
        pending?.let { apply(it) }
    }

    public fun loadFrom(shopsDir: Path): ContentLoader.LoadReport =
        loader.loadFrom(shopsDir, accept = { it.fileName.toString() == FILE_NAME }) { cfg -> catalog = cfg.toCatalog() }

    public companion object {
        public const val FILE_NAME: String = "match-shop.yaml"
    }
}
