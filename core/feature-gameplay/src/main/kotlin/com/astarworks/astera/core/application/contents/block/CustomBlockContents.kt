package com.astarworks.astera.core.application.contents.block

import com.astarworks.astera.core.application.config.block.CustomBlockYamlConfig
import com.astarworks.astera.core.application.contents.YamlDynamicContents
import com.astarworks.astera.core.application.port.outbound.IContentParser
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.block.CustomBlockSpec
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.world.BlockType

/**
 * Custom-block `Contents<BlockType, CustomBlockSpec>` (Wave 5x, ADR-0090).
 *
 * Mirrors [com.astarworks.astera.core.application.contents.ItemContents]:
 *  - `find(id) / all()` from [Contents] (the `ICustomBlockRegistry` surface)
 *  - `bootstrap(dir)` for `onEnable` initial load (best-effort)
 *  - `reload(dir)` for `/astera reload blocks` (all-or-nothing, atomic swap)
 *  - `register(id, spec) / clear()` for tests + admin tools
 *
 * Inverse lookup `resolveByItem(itemId)` walks the current snapshot and
 * returns the first spec whose `placedFrom == itemId`. Registry size stays
 * small (tens to hundreds of custom blocks across all of Astera), so the
 * linear walk is cheaper than maintaining a parallel reverse map that has
 * to stay in lockstep with the atomic snapshot.
 */
public class CustomBlockContents(
    parsers: List<IContentParser> = ContentLoader.DEFAULT_PARSERS,
) : YamlDynamicContents<BlockType, CustomBlockSpec, CustomBlockYamlConfig>(
    categoryName = "blocks",
    deserializer = CustomBlockYamlConfig.serializer(),
    parsers = parsers,
),
    ICustomBlockRegistry {

    /**
     * Convert each YAML DTO to its `(BlockType, CustomBlockSpec)` entry.
     * Throws `IllegalArgumentException` for malformed configs; `ContentLoader`
     * catches and counts as a failed load (so a single bad YAML doesn't kill
     * the whole bootstrap).
     */
    override fun toEntry(config: CustomBlockYamlConfig): Pair<BlockType, CustomBlockSpec> {
        val spec = config.toSpec()
        return spec.id to spec
    }

    override fun resolveByItem(itemId: ItemId): CustomBlockSpec? =
        all().firstOrNull { it.placedFrom == itemId }

    override fun resolveByBaseMaterial(bukkitMaterial: String): CustomBlockSpec? =
        all().firstOrNull { it.visual == null && it.baseBukkitMaterial.equals(bukkitMaterial, ignoreCase = true) }
}
