package com.astarworks.astera.core.domain.item

/**
 * Vendor-neutral item template (Wave _items-1, ADR-0073).
 *
 * Replaces the `IMcItemTemplate` adapter-flavored shape with a plain sealed
 * class so domain code can branch on `Vanilla` vs `External` without pulling
 * in any Minecraft type. Adapter modules read this when materializing an
 * `ItemStack`:
 *
 *   - [Vanilla] — Paper's built-in material catalogue plus optional
 *     `custom_model_data` for resource-pack visual overrides.
 *   - [External] — opaque key resolved by an external plugin (Oraxen,
 *     ItemAdder, MythicItems, …). The plugin is named via `softdepend`; if
 *     it's absent at runtime, the adapter falls back to a stand-in stack.
 *
 * Both variants are pure data — no Bukkit Material lookup happens here.
 * Konsist guards the directional dependency (this file in domain, MC types
 * in adapter).
 */
public sealed class ItemTemplate {

    public data class Vanilla(
        val material: String,
        val customModelData: Int? = null,
    ) : ItemTemplate()

    public data class External(
        val externalKey: String,
    ) : ItemTemplate()
}
