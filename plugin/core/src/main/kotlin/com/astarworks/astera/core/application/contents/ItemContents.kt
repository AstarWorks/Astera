package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.ItemYamlConfig
import com.astarworks.astera.core.application.port.outbound.IContentParser
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Item-side `Contents<ItemId, T>` (Wave _infra-2b, ADR-0065).
 *
 * Replaces the legacy `MutableItemRegistry`. Inherits from
 * [YamlDynamicContents]:
 *
 *   - `find(id) / all()` from [Contents] (the `IItemRegistry` port surface)
 *   - `bootstrap(dir)` for `onEnable` initial load (best-effort)
 *   - `reload(dir)` for `/astera reload items` (all-or-nothing, atomic swap)
 *   - `register(id, template) / clear()` for tests + the `AsteraTestHarness`
 *
 * # Conceptual name
 *
 * Stage 1 D refers to "VanillaItemRegistry"; that's the **default
 * instantiation** where `T` is `IMcItemTemplate` and the [mapper] produces
 * `IMcItemTemplate.Vanilla` from each [ItemYamlConfig]. Other instantiations
 * are legal — tests use `ItemContents<TestItemTemplate>` to avoid pulling in
 * the Minecraft adapter — but the production wiring in `AsteraCorePlugin.kt`
 * builds the vanilla variant.
 *
 * # Why generic over T
 *
 * Application layer must not import `IMc*` (vendor-neutral discipline,
 * ADR-0002). Keeping [ItemContents] generic lets the mapping
 * `ItemYamlConfig -> T` happen at the call site (the platform plugin) where
 * `IMcItemTemplate` is visible. The contents itself stays free of any
 * Minecraft type reference.
 *
 * # Why ContentLoader is reused
 *
 * Per silly-knuth §4.3 (ADR-0016 multi-format), [ContentLoader] is the one
 * place that dispatches YAML / TOML / JSON. The substrate's
 * [YamlDynamicContents] wraps it uniformly across every content type so
 * `content/items/` gets the same "drop a file, it loads" contract as
 * `content/weapons/`.
 *
 * # Wiring example
 *
 * ```kotlin
 * val itemContents = ItemContents<IMcItemTemplate>(
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
 * itemContents.bootstrap(contentDir.resolve("items"))
 * ```
 */
public class ItemContents<T : Any>(
    private val mapper: (ItemYamlConfig) -> T,
    parsers: List<IContentParser> = ContentLoader.DEFAULT_PARSERS,
) : YamlDynamicContents<ItemId, T, ItemYamlConfig>(
    categoryName = "items",
    deserializer = ItemYamlConfig.serializer(),
    parsers = parsers,
),
    IItemRegistry<T> {

    /**
     * Convert each YAML DTO to its `(ItemId, T)` entry. Delegates to
     * [ItemYamlConfig.validate] (throws on bad id / material xor external_key
     * mis-pairing, caught by [ContentLoader] and counted as a failed load)
     * then to the user-supplied [mapper] for the template materialization.
     */
    override fun toEntry(config: ItemYamlConfig): Pair<ItemId, T> {
        val id = config.validate()
        val template = mapper(config)
        return id to template
    }
}
