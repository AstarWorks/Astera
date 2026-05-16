package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.config.ItemYamlConfig
import com.astarworks.astera.core.application.config.parser.JsonContentParser
import com.astarworks.astera.core.application.config.parser.TomlContentParser
import com.astarworks.astera.core.application.config.parser.YamlContentParser
import com.astarworks.astera.core.application.port.outbound.IContentParser
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.item.ItemId
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of [IItemRegistry], populated from `content/items/`
 * via [ContentLoader].
 *
 * # Conceptual name
 *
 * Stage 1 D refers to "VanillaItemRegistry"; that's the **default instantiation**
 * where `T` is `IMcItemTemplate` and the [mapper] produces
 * `IMcItemTemplate.Vanilla` from each [ItemYamlConfig]. Other instantiations
 * are legal — tests use `MutableItemRegistry<FakeItem>` to avoid pulling in
 * the Minecraft adapter — but the production wiring in `AsteraPlugin.kt`
 * builds the vanilla variant.
 *
 * # Why generic over T
 *
 * Application layer must not import `IMc*` (vendor-neutral discipline,
 * ADR-0002). Keeping [MutableItemRegistry] generic lets the mapping
 * `ItemYamlConfig -> T` happen at the call site (the platform plugin) where
 * `IMcItemTemplate` is visible. The registry itself stays free of any
 * Minecraft type reference.
 *
 * # Why ContentLoader is injected
 *
 * Per silly-knuth §4.3 (ADR-0016 multi-format), [ContentLoader] is the one
 * place that dispatches YAML / TOML / JSON. Reusing it gives `content/items/`
 * the same "drop a file, it loads" contract as `content/weapons/`. The
 * [DEFAULT_PARSERS] convenience matches `WeaponLoaderService.DEFAULT_PARSERS`.
 *
 * # Wiring example
 *
 * ```kotlin
 * val itemRegistry = MutableItemRegistry<IMcItemTemplate>(
 *     mapper = { cfg ->
 *         if (cfg.externalKey != null) IMcItemTemplate.External(ItemId(cfg.id), cfg.externalKey!!)
 *         else IMcItemTemplate.Vanilla(
 *             id = ItemId(cfg.id),
 *             material = cfg.material!!,
 *             customModelData = cfg.customModelData,
 *             displayName = cfg.displayNameKey?.let(::MessageKey),
 *             lore = cfg.loreKeys.map(::MessageKey),
 *             pdcTags = cfg.pdcTags,
 *         )
 *     },
 * )
 * itemRegistry.loadFrom(contentDir.resolve("items"))
 * ```
 */
public class MutableItemRegistry<T : Any>(
    private val mapper: (ItemYamlConfig) -> T,
    parsers: List<IContentParser> = DEFAULT_PARSERS,
) : IItemRegistry<T> {

    private val store = ConcurrentHashMap<ItemId, T>()

    private val loader = ContentLoader(
        parsers = parsers,
        deserializer = ItemYamlConfig.serializer(),
    )

    /** Outcome of a [loadFrom] invocation; same shape as `ContentLoader.LoadReport`. */
    public data class LoadReport(val loaded: Int, val failed: Int)

    /**
     * Walks [dir] non-recursively and registers each well-formed
     * [ItemYamlConfig] entry. Files with invalid `id` / inconsistent
     * `material` vs `external_key` are logged at ERROR and counted as failed
     * — same fail-soft semantics as `WeaponLoaderService.loadFrom`.
     */
    public fun loadFrom(dir: Path): LoadReport {
        val report = loader.loadFrom(dir) { cfg ->
            // validate() throws IllegalArgumentException for bad ids or
            // material/external_key mis-pairing; ContentLoader catches
            // Throwable from this callback and counts the file as failed
            // (uniform fail-soft semantics with WeaponLoaderService).
            val id = cfg.validate()
            val template = mapper(cfg)
            store[id] = template
        }
        return LoadReport(loaded = report.loaded, failed = report.failed)
    }

    /** Manual registration (used by tests and Phase 4 admin commands). */
    public fun register(id: ItemId, template: T) {
        store[id] = template
    }

    /** Drop every registered entry. Used by Phase 4 hot-reload. */
    public fun clear() {
        store.clear()
    }

    override fun resolveOrNull(id: ItemId): T? = store[id]
    override fun list(): Collection<T> = store.values.toList()

    public companion object {
        /**
         * Default parser list mirrors `WeaponLoaderService.DEFAULT_PARSERS`:
         * YAML + TOML + JSON per ADR-0016 Phase 2 mid wire-format matrix.
         */
        public val DEFAULT_PARSERS: List<IContentParser> = listOf(
            YamlContentParser(),
            TomlContentParser(),
            JsonContentParser(),
        )
    }
}
