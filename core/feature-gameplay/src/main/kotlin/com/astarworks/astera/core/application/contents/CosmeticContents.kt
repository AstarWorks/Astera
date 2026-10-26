package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.cosmetic.CosmeticYamlConfig
import com.astarworks.astera.core.application.port.outbound.IContentParser
import com.astarworks.astera.core.application.port.outbound.cosmetic.ICosmeticCatalog
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.cosmetic.Cosmetic
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** The `content/cosmetics` registry (one yaml per cosmetic); reload swaps atomically and keeps the old state on any parse failure. */
public class CosmeticContents(
    parsers: List<IContentParser> = ContentLoader.DEFAULT_PARSERS,
) : ReloadableContents, ICosmeticCatalog {

    override val categoryName: String = "cosmetics"

    private val store: AtomicReference<Map<String, Cosmetic>> = AtomicReference(emptyMap())
    private val loader = ContentLoader(parsers, CosmeticYamlConfig.serializer())

    override fun all(): List<Cosmetic> = store.get().values.toList()

    override fun find(id: String): Cosmetic? = store.get()[id]

    /** Best-effort initial load. */
    public fun bootstrap(dir: Path): ContentLoader.LoadReport {
        val incoming = LinkedHashMap<String, Cosmetic>()
        val report = loader.loadFrom(dir) { cfg -> cfg.toDomain().let { incoming[it.id] = it } }
        store.set(incoming.toMap())
        return report
    }

    override fun reload(dir: Path): ContentLoader.LoadReport {
        val next = LinkedHashMap<String, Cosmetic>()
        val report = loader.loadFrom(dir) { cfg -> cfg.toDomain().let { next[it.id] = it } }
        if (report.failed == 0) store.set(next.toMap())
        return report
    }
}
