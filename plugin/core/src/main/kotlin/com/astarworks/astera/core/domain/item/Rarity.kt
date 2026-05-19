package com.astarworks.astera.core.domain.item

/**
 * Item rarity tier (Wave _items-1, ADR-0073).
 *
 * Promoted from `:plugin:weapons` to `:plugin:core` because every item — not
 * just weapons — now declares a rarity inline on its [ItemSpec]. The tier is
 * pure vocabulary; visual treatment (lore colour, glint default, drop chance
 * weighting) is decided downstream by renderers / loot tables.
 */
public enum class Rarity { COMMON, UNCOMMON, RARE, EPIC, LEGENDARY }
