package com.astarworks.astera.core.adapter.api.item

import com.astarworks.astera.core.domain.item.ItemId

/**
 * Vendor-neutral "render this exact stack" instruction for the adapter layer.
 *
 * Wave _items-1 (ADR-0073) demoted `IMcItemTemplate` from the application
 * layer's primary item type to a **pre-rendered, adapter-boundary template**.
 * The canonical materialized item shape is now
 * [com.astarworks.astera.core.domain.item.ItemSpec], which lives entirely in
 * `core.domain.item.*` and carries inline [com.astarworks.astera.core.domain.i18n.LocalizedText]
 * / [com.astarworks.astera.core.domain.i18n.LocalizedLore] for display name
 * and lore. The application layer reads `ItemSpec` straight from
 * [com.astarworks.astera.core.application.port.outbound.IItemRegistry] (no
 * generic, no `MessageKey` lookup).
 *
 * `IMcItemTemplate` is what the *adapter* layer asks for at give-time:
 * `ItemSpec` is converted to this shape by
 * [com.astarworks.astera.core.application.service.toAdapterTemplate] (a tiny
 * conversion that resolves the inline i18n via `IMessageRenderer`), and the
 * Paper / Folia / web impl then materializes it through whatever native stack
 * type it has. Per ADR-0002, this interface MUST NOT import `org.bukkit.*` /
 * `io.papermc.*` — Konsist enforces it.
 *
 * Two flavors:
 *
 * - [Vanilla] — described in Astera's own terms (Bukkit Material *name*,
 *   custom model data, pre-rendered MiniMessage display name + lore, opaque
 *   PDC tags). The Paper impl materializes this with
 *   `Material.matchMaterial(...)` and the Adventure component pipeline.
 * - [External] — opaque identifier of an item registered by a third-party
 *   plugin (`oraxen:my_blade`, `itemadder:legendary_axe`, …). The adapter
 *   module for that provider resolves it; Astera itself doesn't care.
 *
 * Display name + lore are **already-resolved MiniMessage strings** at this
 * layer — the application-side conversion does the locale lookup, so the
 * adapter does not need to know about `IMessageRenderer` or per-viewer
 * locales. This is the contract change that lets `ItemSpec`'s inline i18n
 * stay in the domain layer (ADR-0073 §i18n).
 *
 * PDC tags ([Vanilla.pdcTags]) are an open key/value bag the adapter writes
 * into Bukkit `PersistentDataContainer` so the same item can be recognized
 * later (the canonical `astera:item-id` tag is written automatically; these
 * are extras the spec may carry).
 */
public sealed interface IMcItemTemplate {

    /** The id this template was registered under. */
    public val id: ItemId

    /**
     * A vanilla-described item: a Bukkit `Material` *name* (not the enum
     * itself — Astera stays vendor-neutral here), optional custom model data
     * for resource-pack-driven visuals, optional pre-rendered display name +
     * lore, and arbitrary PDC string tags for adapter-side identification.
     *
     * @property material Bukkit Material name, e.g. `IRON_SWORD`. The adapter
     *   resolves this with `Material.matchMaterial` and falls back to `STONE`
     *   on miss (the failure surface is logged, not silent).
     * @property customModelData Resource-pack `custom_model_data` integer.
     *   `null` means "leave unset" — most items don't need it.
     * @property displayName Pre-rendered MiniMessage string for the in-game
     *   item name (or `null` to leave the default Material name). The
     *   application layer resolved this from the spec's inline
     *   [com.astarworks.astera.core.domain.i18n.LocalizedText] before
     *   handing the template to the adapter.
     * @property lore Ordered list of pre-rendered MiniMessage strings; each
     *   becomes one lore line. Empty means "no lore section".
     * @property pdcTags Arbitrary string key/value pairs the adapter writes
     *   into the item's PDC at materialize time.
     */
    public data class Vanilla(
        override val id: ItemId,
        val material: String,
        val customModelData: Int? = null,
        val displayName: String? = null,
        val lore: List<String> = emptyList(),
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
