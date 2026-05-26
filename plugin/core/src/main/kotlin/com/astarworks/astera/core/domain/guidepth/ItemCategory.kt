package com.astarworks.astera.core.domain.guidepth

/**
 * Free-form item categorisation tag used by [TooltipScope.ByCategory]
 * (Wave 5gui-depth, ADR-0114).
 *
 * Astera does not maintain a single global enum of item categories — different
 * features (combat / survival / cosmetic) categorise the same item along
 * orthogonal axes. `ItemCategory` is therefore a free-form string value class
 * with kebab-case discipline (`pvp-weapon`, `gathering-tool`,
 * `cosmetic-skin`), matching [com.astarworks.astera.core.domain.item.ItemId]
 * naming.
 *
 * Categorising an item against a category is the caller's responsibility (a
 * future `IItemCategoryRegistry` may emerge once usage settles); the gui-depth
 * tooltip system only takes the category as a scope key.
 *
 * Naming rule: lowercase kebab-case (`pvp-weapon`, `survival-tool`,
 * `boss-loot`). Validated at construction so YAML typos / contributor
 * inconsistencies surface immediately — same regex as [com.astarworks.astera.core.domain.item.ItemId].
 */
@JvmInline
public value class ItemCategory(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "ItemCategory must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
