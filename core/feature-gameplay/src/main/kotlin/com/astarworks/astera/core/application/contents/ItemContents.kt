package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.ItemYamlConfig
import com.astarworks.astera.core.application.port.outbound.IContentParser
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSpec

/**
 * Item-side `Contents<ItemId, ItemSpec>` (Wave _infra-2b / ADR-0065;
 * simplified in Wave _items-1 / ADR-0073).
 *
 * Replaces the legacy `MutableItemRegistry`. Inherits from
 * [YamlDynamicContents]:
 *
 *   - `find(id) / all()` from [Contents] (the `IItemRegistry` port surface)
 *   - `bootstrap(dir)` for `onEnable` initial load (best-effort)
 *   - `reload(dir)` for `/astera reload items` (all-or-nothing, atomic swap)
 *   - `register(id, spec) / clear()` for tests + the `AsteraTestHarness`
 *
 * # Wave _items-1 simplification (ADR-0073)
 *
 * Pre-_items-1 this class was generic over `T : Any` so the caller could pick
 * between `IMcItemTemplate` (Paper) and a test stand-in. After _items-1 the
 * canonical materialized form is [ItemSpec] — which is pure domain, vendor-
 * neutral, and carries all the per-item state that adapter-flavored templates
 * used to. The generic + `mapper` lambda are gone; [ItemYamlConfig.toSpec]
 * handles the mapping inside `toEntry`.
 *
 * # Auto-i18n
 *
 * Each [ItemSpec] carries its [com.astarworks.astera.core.domain.i18n.LocalizedText]
 * `displayName` and [com.astarworks.astera.core.domain.i18n.LocalizedLore]
 * `lore` inline — no `MessageKey` lookup against `content/languages/`. The
 * renderer resolves them via `IMessageRenderer.renderText` /
 * `IMessageRenderer.renderLocalizedLore`.
 *
 * # Why ContentLoader is reused
 *
 * Per silly-knuth §4.3 (ADR-0016 multi-format), [ContentLoader] is the one
 * place that dispatches YAML / TOML / JSON. The substrate's
 * [YamlDynamicContents] wraps it uniformly across every content type so
 * `content/items/` gets the same "drop a file, it loads" contract as
 * `content/weapons/`.
 */
public class ItemContents(
    parsers: List<IContentParser> = ContentLoader.DEFAULT_PARSERS,
) : YamlDynamicContents<ItemId, ItemSpec, ItemYamlConfig>(
    categoryName = "items",
    deserializer = ItemYamlConfig.serializer(),
    parsers = parsers,
),
    IItemRegistry {

    /**
     * Convert each YAML DTO to its `(ItemId, ItemSpec)` entry. Delegates to
     * [ItemYamlConfig.toSpec], which handles id parsing, material xor
     * external_key validation, and every sub-record materialization. Any
     * `IllegalArgumentException` is caught by [ContentLoader] and counted as
     * a failed load.
     */
    override fun toEntry(config: ItemYamlConfig): Pair<ItemId, ItemSpec> {
        val spec = config.toSpec()
        return spec.id to spec
    }
}
