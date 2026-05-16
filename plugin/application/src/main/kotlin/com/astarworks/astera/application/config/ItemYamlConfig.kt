package com.astarworks.astera.application.config

import com.astarworks.astera.domain.model.item.ItemId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for `content/items/` definition files.
 *
 * Each `content/items/` YAML file is the SSoT for every item Astera registers. Each
 * file becomes one entry in `IItemRegistry`; weapons / GUI slots / loadouts
 * later reference these by [id]. Kept separate from any domain or adapter
 * type so the schema can evolve (e.g. add `external_key` for Oraxen) without
 * pushing serialization annotations into other layers (ADR-0016, Stage 1 D).
 *
 * # Wire format (vanilla)
 *
 * ```yaml
 * id: example-sword
 * material: IRON_SWORD               # Bukkit Material name; required for vanilla
 * custom_model_data: 1001            # optional, resource-pack visual override
 * display_name_key: astera.item.example-sword.name
 * lore_keys:
 *   - astera.item.example-sword.lore.1
 *   - astera.item.example-sword.lore.2
 * pdc_tags:
 *   astera:rarity: common
 * ```
 *
 * # Wire format (external — Oraxen / ItemAdder / MythicItems)
 *
 * ```yaml
 * id: my-special-blade
 * external_key: oraxen:my_blade
 * ```
 *
 * Exactly one of [material] / [externalKey] must be set; both / neither is
 * rejected at parse-time by [validate]. Adapter modules consume this DTO via
 * `MutableItemRegistry`'s mapper lambda — the application layer itself never
 * constructs `IMcItemTemplate`, keeping it free of `IMc*` references.
 */
@Serializable
public data class ItemYamlConfig(
    public val id: String,
    public val material: String? = null,
    @SerialName("custom_model_data") public val customModelData: Int? = null,
    @SerialName("display_name_key") public val displayNameKey: String? = null,
    @SerialName("lore_keys") public val loreKeys: List<String> = emptyList(),
    @SerialName("pdc_tags") public val pdcTags: Map<String, String> = emptyMap(),
    @SerialName("external_key") public val externalKey: String? = null,
) {

    /**
     * Parse the [id] into a domain [ItemId], surfacing any kebab-case
     * violation as an `IllegalArgumentException` (ContentLoader logs +
     * skips). Called early so a malformed id never leaks into the registry.
     */
    public fun toItemId(): ItemId = ItemId(id)

    /**
     * Returns the parsed [ItemId] iff this config is well-formed (exactly
     * one of `material` / `external_key` is set, and the id parses). Throws
     * `IllegalArgumentException` otherwise — surfaced by ContentLoader.
     */
    public fun validate(): ItemId {
        val itemId = toItemId()
        val hasMaterial = material != null
        val hasExternal = externalKey != null
        require(hasMaterial xor hasExternal) {
            "ItemYamlConfig '$id' must specify exactly one of `material` or `external_key`."
        }
        return itemId
    }
}
