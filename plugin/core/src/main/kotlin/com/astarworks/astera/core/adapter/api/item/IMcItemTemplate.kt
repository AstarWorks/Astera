package com.astarworks.astera.core.adapter.api.item

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Vendor-neutral description of how to render an item identified by [ItemId].
 *
 * `IMcItemTemplate` is what the application layer asks `IItemRegistry` for and
 * hands to an `IMcPlayer` / `IMcInventory` renderer; the adapter layer is the
 * only place that knows whether the template materializes via Bukkit
 * `ItemStack`, Oraxen lookup, ItemAdder etc. Per ADR-0002, this interface MUST
 * NOT import `org.bukkit.*` / `io.papermc.*` — Konsist enforces it.
 *
 * Two flavors:
 *
 * - [Vanilla] — described in Astera's own terms (Bukkit Material *name*,
 *   custom model data, MiniMessage display name + lore, opaque PDC tags).
 *   The Paper impl materializes this with `Material.matchMaterial(...)` and
 *   the Adventure component pipeline.
 * - [External] — opaque identifier of an item registered by a third-party
 *   plugin (`oraxen:my_blade`, `itemadder:legendary_axe`, …). The adapter
 *   module for that provider resolves it; Astera itself doesn't care.
 *
 * Display name + lore are [MessageKey]s, not raw strings, so item content
 * follows the same i18n SSoT as the rest of Astera. The renderer resolves
 * them through `IMessageRenderer` per viewer locale at render time.
 *
 * PDC tags ([Vanilla.pdcTags]) are an open key/value bag the adapter writes
 * into Bukkit `PersistentDataContainer` so the same item can be recognized
 * later (Stage 2 H replaces "material match" weapon detection with PDC tag
 * lookup, using these entries plus an `astera:item-id` tag the adapter
 * inserts unconditionally).
 */
public sealed interface IMcItemTemplate {

    /** The id this template was registered under. */
    public val id: ItemId

    /**
     * A vanilla-described item: a Bukkit `Material` *name* (not the enum
     * itself — Astera stays vendor-neutral here), optional custom model data
     * for resource-pack-driven visuals, optional i18n display name + lore,
     * and arbitrary PDC string tags for adapter-side identification.
     *
     * @property material Bukkit Material name, e.g. `IRON_SWORD`. The adapter
     *   resolves this with `Material.matchMaterial` and falls back to `STONE`
     *   on miss (the failure surface is logged, not silent).
     * @property customModelData Resource-pack `custom_model_data` integer.
     *   `null` means "leave unset" — most items don't need it.
     * @property displayName Optional i18n key for the in-game item name. The
     *   renderer resolves this per-viewer.
     * @property lore Ordered list of i18n keys; each becomes one lore line.
     * @property pdcTags Arbitrary string key/value pairs the adapter writes
     *   into the item's PDC at materialize time.
     */
    public data class Vanilla(
        override val id: ItemId,
        val material: String,
        val customModelData: Int? = null,
        val displayName: MessageKey? = null,
        val lore: List<MessageKey> = emptyList(),
        val pdcTags: Map<String, String> = emptyMap(),
    ) : IMcItemTemplate {
        init {
            require(material.isNotBlank()) { "IMcItemTemplate.Vanilla.material must not be blank" }
            require(customModelData == null || customModelData >= 0) {
                "IMcItemTemplate.Vanilla.customModelData must be >= 0 or null: $customModelData"
            }
        }
    }

    /**
     * A reference to an item registered by an external plugin (Oraxen,
     * ItemAdder, MythicItems, …). The adapter module for that provider is
     * responsible for resolving the key — Astera core does not interpret it.
     *
     * @property key Provider-specific identifier, e.g. `oraxen:my_blade`.
     *   By convention the prefix names the provider, but the registry does
     *   not enforce that — it's just an opaque string Astera hands to the
     *   matching adapter.
     */
    public data class External(
        override val id: ItemId,
        val key: String,
    ) : IMcItemTemplate {
        init {
            require(key.isNotBlank()) { "IMcItemTemplate.External.key must not be blank" }
        }
    }
}
