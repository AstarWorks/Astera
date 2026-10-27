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

    public fun loadFrom(shopsDir: Path): ContentLoader.LoadReport =
        loader.loadFrom(shopsDir, accept = { it.fileName.toString() == FILE_NAME }) { cfg -> catalog = cfg.toCatalog() }

    public companion object {
        public const val FILE_NAME: String = "match-shop.yaml"
    }
}
