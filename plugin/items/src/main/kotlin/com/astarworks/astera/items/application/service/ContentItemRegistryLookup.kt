package com.astarworks.astera.items.application.service

import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.item.IContentItemRegistryLookup
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemTemplate

/**
 * [IContentItemRegistryLookup] impl backed by the items plugin's
 * [IItemRegistry] (Wave 6c §C, ADR-0160).
 *
 * Closes the WARN+null gap left by Wave 6b-3 (ADR-0158): the Paper
 * inventory serializer can now resolve a
 * [com.astarworks.astera.core.domain.player.InventoryItemSlot.ContentItem]
 * back to the right vanilla `ItemStack` material instead of dropping the
 * slot.
 *
 * # Resolution algorithm
 *
 * 1. Strip the optional `"astera:"` namespace prefix from [rawItemId].
 * 2. Build an [ItemId] from the bare id (`ItemId` rejects malformed forms
 *    with [IllegalArgumentException]; we catch + return `null` so the
 *    decode path doesn't crash on stray characters).
 * 3. Look the id up in the registry.
 * 4. If the resolved [com.astarworks.astera.core.domain.item.ItemSpec.template]
 *    is [ItemTemplate.Vanilla], return its `material` string verbatim
 *    (already in the `"minecraft:diamond_sword"` shape that
 *    `Material.matchMaterial(...)` accepts).
 * 5. [ItemTemplate.External] templates return `null` — external providers
 *    (Oraxen / ItemAdder / MythicItems) own the material at give-time,
 *    not the snapshot restorer.
 *
 * # Why namespace stripping happens here, not in the port contract
 *
 * The persisted shape (`"astera:<id>"`) is a Wave 5e domain convention. The
 * domain class [com.astarworks.astera.core.domain.player.InventoryItemSlot
 * .ContentItem] holds the prefixed string as opaque text — it doesn't parse
 * it. Keeping the namespace handling inside the impl means the port stays
 * neutral about how content packs identify themselves; if a future content
 * pack publishes under a different namespace, only the impl needs an
 * additional clause.
 */
public class ContentItemRegistryLookup(
    private val registry: IItemRegistry,
) : IContentItemRegistryLookup {

    override fun bukkitMaterialKeyFor(rawItemId: String): String? {
        val bareId = stripNamespace(rawItemId) ?: return null
        val itemId = parseItemId(bareId) ?: return null
        val spec = registry.find(itemId) ?: return null
        return when (val template = spec.template) {
            is ItemTemplate.Vanilla -> template.material
            is ItemTemplate.External -> null
        }
    }

    /**
     * Drop the optional `"astera:"` prefix. Returns the bare id, or `null`
     * if the input has a colon with anything other than the Astera
     * namespace (unknown content pack ⇒ caller treats as miss).
     */
    private fun stripNamespace(raw: String): String? {
        val colon = raw.indexOf(':')
        if (colon < 0) return raw
        val namespace = raw.substring(0, colon)
        val rest = raw.substring(colon + 1)
        return if (namespace == ASTERA_NAMESPACE) rest else null
    }

    /**
     * Build an [ItemId] from a bare string, swallowing the constructor's
     * validation [IllegalArgumentException] as a `null` miss. The Konsist
     * "application layer must not import raw JVM throwables" rule allows
     * private-helper catches (the public surface stays exception-free).
     */
    private fun parseItemId(bareId: String): ItemId? = try {
        ItemId(bareId)
    } catch (_: IllegalArgumentException) {
        null
    }

    public companion object {
        /** Default content-pack namespace prefix in persisted ContentItem ids. */
        public const val ASTERA_NAMESPACE: String = "astera"
    }
}
